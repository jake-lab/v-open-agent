"""TASK-07 window, freshness, sensor health, and frame assembly tests."""

from tests.builders import build_frame, build_observation
from voa.contracts.observation import DetectionPayload, HealthPayload, TrackPayload
from voa.engine.profile import PolicyProfile
from voa.engine.window import FrameAssembler, ObservationWindow


def policy(
    *,
    staleness_ms: int = 1_000,
    window_ms: int = 5_000,
    window_max_items: int = 256,
) -> PolicyProfile:
    return PolicyProfile.model_validate(
        {
            "profile_id": "test",
            "profile_version": 1,
            "degraded_notice_text": "감지가 제한됩니다.",
            "params": {
                "min_score": 0.5,
                "roi_min_overlap": 0.3,
                "confirm_frames": 3,
                "clear_frames": 3,
                "staleness_ms": staleness_ms,
                "unknown_after_ms": staleness_ms,
                "warning_ttl_ms": 4_000,
                "window_ms": window_ms,
                "window_max_items": window_max_items,
            },
            "scenarios": [
                {
                    "id": "moving",
                    "required_kinds": ["object_detection", "track_state"],
                    "required_streams": ["camera_0"],
                    "labels": ["person"],
                    "motion_classes": ["moving"],
                    "roi": [0.0, 0.0, 1.0, 1.0],
                    "blocking_flags": ["occluded"],
                    "warning_text": "이동 물체에 주의하세요.",
                    "severity": "attention",
                },
                {
                    "id": "obstacle",
                    "required_kinds": ["object_detection"],
                    "required_streams": ["camera_0"],
                    "labels": ["obstacle"],
                    "motion_classes": [],
                    "roi": [0.0, 0.0, 1.0, 1.0],
                    "blocking_flags": ["occluded"],
                    "warning_text": "장애물에 주의하세요.",
                    "severity": "attention",
                },
            ],
        }
    )


def test_freshness_boundary_is_inclusive_and_lookup_is_read_only() -> None:
    window = ObservationWindow(policy(staleness_ms=1, window_ms=2).params)
    observation = build_observation(server_received_mono_ns=10)

    assert window.add(observation, now_mono_ns=10) == ()
    assert window.get(observation.observation_id) is observation
    assert window.is_fresh(observation.observation_id, now_mono_ns=1_000_010)
    assert not window.is_fresh(observation.observation_id, now_mono_ns=1_000_011)
    assert window.get(observation.observation_id) is observation


def test_item_limit_is_per_stream_kind_and_keeps_id_index_in_sync() -> None:
    window = ObservationWindow(policy(window_max_items=2).params)
    first = build_observation(observation_id="det_0", seq=0)
    second = build_observation(observation_id="det_1", seq=1)
    third = build_observation(observation_id="det_2", seq=2)
    track = build_observation(
        observation_id="track_0",
        payload=TrackPayload(kind="track_state", frame_id="frame_track", tracks=[]),
    )

    assert window.add(first, now_mono_ns=0) == ()
    assert window.add(track, now_mono_ns=0) == ()
    assert window.add(second, now_mono_ns=0) == ()
    assert window.add(third, now_mono_ns=0) == ("det_0",)
    assert window.get("det_0") is None
    assert window.get("det_1") is second
    assert window.get("det_2") is third
    assert window.get("track_0") is track


def test_window_age_boundary_is_inclusive_then_evicts_id_one_nanosecond_later() -> None:
    window = ObservationWindow(policy(staleness_ms=1, window_ms=2).params)
    observation = build_observation(server_received_mono_ns=10)
    window.add(observation, now_mono_ns=10)

    assert window.expire(now_mono_ns=2_000_010) == ()
    assert window.get(observation.observation_id) is observation
    assert window.expire(now_mono_ns=2_000_011) == (observation.observation_id,)
    assert window.get(observation.observation_id) is None


def test_health_observations_update_sensor_down_without_resetting_on_eviction() -> None:
    window = ObservationWindow(policy(window_max_items=1).params)
    assert not window.is_sensor_down("camera_0")

    statuses = ["down", "degraded", "down", "ok"]
    expected = [True, False, True, False]
    for seq, (status, is_down) in enumerate(zip(statuses, expected, strict=True)):
        observation = build_observation(
            observation_id=f"health_{seq}",
            seq=seq,
            payload=HealthPayload(kind="health", status=status, detail_code="test"),
        )
        window.add(observation, now_mono_ns=0)
        assert window.is_sensor_down("camera_0") is is_down

    window.expire(now_mono_ns=10_000_000_000)
    assert not window.is_sensor_down("camera_0")


def test_scenarios_assemble_independently_and_order_required_kinds() -> None:
    assembler = FrameAssembler(policy())
    detection, track = build_frame(frame_id="frame_1")

    detection_frames = assembler.add(detection, now_mono_ns=0)
    assert [(frame.scenario_id, frame.frame_id) for frame in detection_frames] == [
        ("obstacle", "frame_1")
    ]
    assert detection_frames[0].observations == (detection,)

    track_frames = assembler.add(track, now_mono_ns=0)
    assert [(frame.scenario_id, frame.frame_id) for frame in track_frames] == [
        ("moving", "frame_1")
    ]
    assert track_frames[0].observations == (detection, track)


def test_irrelevant_stream_and_missing_kind_do_not_complete_frame() -> None:
    assembler = FrameAssembler(policy())
    other_stream = build_observation(stream_id="camera_1")
    detection, _ = build_frame(frame_id="missing_track")

    assert assembler.add(other_stream, now_mono_ns=0) == ()
    frames = assembler.add(detection, now_mono_ns=0)
    assert [frame.scenario_id for frame in frames] == ["obstacle"]


def test_completed_frame_is_emitted_once_and_late_observations_are_ignored() -> None:
    assembler = FrameAssembler(policy())
    detection, track = build_frame(frame_id="frame_once")
    assembler.add(detection, now_mono_ns=0)
    assert [frame.scenario_id for frame in assembler.add(track, now_mono_ns=0)] == ["moving"]

    late_detection, late_track = build_frame(
        frame_id="frame_once",
        observation_ids=("late_detection", "late_track"),
        seq=1,
    )
    assert assembler.add(late_detection, now_mono_ns=1) == ()
    assert assembler.add(late_track, now_mono_ns=1) == ()


def test_completed_frame_id_is_remembered_through_window_boundary() -> None:
    assembler = FrameAssembler(policy(staleness_ms=1, window_ms=1))
    detection, track = build_frame(frame_id="reusable_frame")
    assembler.add(detection, now_mono_ns=0)
    assembler.add(track, now_mono_ns=0)

    boundary_detection, _ = build_frame(
        frame_id="reusable_frame",
        observation_ids=("boundary_detection", "boundary_track"),
        seq=1,
        server_received_mono_ns=1_000_000,
    )
    assert assembler.add(boundary_detection, now_mono_ns=1_000_000) == ()

    fresh_detection, fresh_track = build_frame(
        frame_id="reusable_frame",
        observation_ids=("fresh_detection", "fresh_track"),
        seq=2,
        server_received_mono_ns=1_000_001,
    )
    assert [
        frame.scenario_id for frame in assembler.add(fresh_detection, now_mono_ns=1_000_001)
    ] == ["obstacle"]
    assert [frame.scenario_id for frame in assembler.add(fresh_track, now_mono_ns=1_000_001)] == [
        "moving"
    ]


def test_stale_completed_frame_is_discarded_and_not_reopened() -> None:
    assembler = FrameAssembler(policy(staleness_ms=1, window_ms=2))
    detection, _ = build_frame(frame_id="stale_frame")
    late_track = build_observation(
        observation_id="late_track",
        seq=1,
        server_received_mono_ns=1_000_001,
        payload=TrackPayload(kind="track_state", frame_id="stale_frame", tracks=[]),
    )
    assembler.add(detection, now_mono_ns=0)

    frames = assembler.add(late_track, now_mono_ns=1_000_001)
    assert all(frame.scenario_id != "moving" for frame in frames)

    fresh_detection = build_observation(
        observation_id="fresh_detection",
        seq=2,
        server_received_mono_ns=1_000_001,
        payload=DetectionPayload(kind="object_detection", frame_id="stale_frame", detections=[]),
    )
    assert assembler.add(fresh_detection, now_mono_ns=1_000_001) == ()


def test_expired_incomplete_frame_does_not_combine_with_later_kind() -> None:
    assembler = FrameAssembler(policy(staleness_ms=1, window_ms=1))
    detection, _ = build_frame(frame_id="expired_frame")
    assembler.add(detection, now_mono_ns=0)
    assembler.expire(now_mono_ns=1_000_001)

    track = build_observation(
        observation_id="track_after_expiry",
        seq=1,
        server_received_mono_ns=1_000_001,
        payload=TrackPayload(kind="track_state", frame_id="expired_frame", tracks=[]),
    )
    frames = assembler.add(track, now_mono_ns=1_000_001)
    assert all(frame.scenario_id != "moving" for frame in frames)
