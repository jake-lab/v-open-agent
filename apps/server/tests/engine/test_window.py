"""TASK-07 retention, evidence freshness, health and frame assembly."""

import pytest

from tests.builders import build_frame, build_observation
from voa.contracts.enums import HealthStatus
from voa.contracts.observation import HealthPayload, Observation
from voa.engine.admission import Admission
from voa.engine.profile import PolicyParams, PolicyProfile, ScenarioProfile
from voa.engine.window import FrameAssembler, ObservationWindow

MS = 1_000_000


def make_window(
    *, window_max_items: int = 2, window_ms: int = 5, staleness_ms: int = 1
) -> ObservationWindow:
    return ObservationWindow(
        PolicyParams(
            min_score=0.5,
            roi_min_overlap=0.3,
            confirm_frames=3,
            clear_frames=3,
            staleness_ms=staleness_ms,
            unknown_after_ms=staleness_ms,
            warning_ttl_ms=4_000,
            window_ms=window_ms,
            window_max_items=window_max_items,
        )
    )


def make_scenario(*, tracked: bool = True) -> ScenarioProfile:
    return ScenarioProfile(
        id="moving" if tracked else "obstacle",
        required_kinds=["object_detection", "track_state"] if tracked else ["object_detection"],
        required_streams=["camera_0"],
        labels=["person"] if tracked else ["obstacle"],
        motion_classes=["moving"] if tracked else [],
        roi=(0.0, 0.0, 1.0, 1.0),
        blocking_flags=["occluded"],
        warning_text="전방에 주의하세요.",
        severity="attention",
    )


def consume(
    assembler: FrameAssembler, observation: Observation, *, now_mono_ns: int = 0
) -> tuple[Observation, ...] | None:
    assembler.window.append(observation, now_mono_ns=now_mono_ns)
    return assembler.add(observation, now_mono_ns=now_mono_ns)


@pytest.mark.parametrize(("offset_ns", "fresh"), [(-1, True), (0, True), (1, False)])
def test_freshness_uses_server_time_with_inclusive_boundary(offset_ns: int, fresh: bool) -> None:
    window = make_window()
    observation = build_observation(
        server_received_mono_ns=10 * MS,
        capture_ts_monotonic_ns=10**18,
        clock_quality="unknown",
    )
    window.append(observation, now_mono_ns=10 * MS)

    assert window.is_fresh(observation.observation_id, now_mono_ns=11 * MS + offset_ns) is fresh
    assert not window.is_fresh("missing", now_mono_ns=10 * MS)


def test_queries_do_not_evict_evidence_or_advance_time() -> None:
    window = make_window()
    observation = build_observation()
    window.append(observation, now_mono_ns=0)

    assert not window.is_fresh(observation.observation_id, now_mono_ns=100 * MS)
    assert window.get_observation(observation.observation_id) is observation
    assert window.is_fresh(observation.observation_id, now_mono_ns=0)
    assert window.prune(now_mono_ns=0) == ()
    assert window.prune(now_mono_ns=100 * MS) == (observation.observation_id,)
    assert window.get_observation(observation.observation_id) is None
    assert not window.is_fresh(observation.observation_id, now_mono_ns=0)


def test_capacity_is_per_stream_and_kind_and_synchronizes_index() -> None:
    window = make_window()
    detection, track = build_frame()
    other_stream = build_observation(observation_id="other_stream", stream_id="camera_1")
    for observation in (detection, track, other_stream):
        assert window.append(observation, now_mono_ns=0) == ()
    newer = build_observation(observation_id="newer", seq=1)
    newest = build_observation(observation_id="newest", seq=2)

    assert window.append(newer, now_mono_ns=0) == ()
    assert window.append(newest, now_mono_ns=0) == (detection.observation_id,)
    assert window.get_observation(detection.observation_id) is None
    for retained in (track, other_stream, newer, newest):
        assert window.get_observation(retained.observation_id) is retained


def test_retention_boundary_and_tick_prune_cover_all_keys() -> None:
    window = make_window()
    detection, track = build_frame()
    other_stream = build_observation(observation_id="other_stream", stream_id="camera_1")
    for observation in (detection, track, other_stream):
        window.append(observation, now_mono_ns=0)

    assert window.prune(now_mono_ns=5 * MS) == ()
    assert window.prune(now_mono_ns=5 * MS + 1) == (
        detection.observation_id,
        track.observation_id,
        other_stream.observation_id,
    )
    for observation in (detection, track, other_stream):
        assert window.get_observation(observation.observation_id) is None
    assert window.prune(now_mono_ns=6 * MS) == ()
    replacement = build_observation(observation_id="replacement", server_received_mono_ns=6 * MS)
    assert window.append(replacement, now_mono_ns=6 * MS) == ()


def test_append_expires_other_keys_and_keeps_new_input() -> None:
    window = make_window()
    detection, track = build_frame()
    window.append(detection, now_mono_ns=0)
    window.append(track, now_mono_ns=0)
    new = build_observation(
        observation_id="new_stream", stream_id="camera_1", server_received_mono_ns=6 * MS
    )

    assert window.append(new, now_mono_ns=6 * MS) == (
        detection.observation_id,
        track.observation_id,
    )
    assert window.get_observation(new.observation_id) is new


def test_prune_handles_receipt_times_that_differ_from_queue_order() -> None:
    window = make_window()
    newer = build_observation(observation_id="newer", server_received_mono_ns=2 * MS)
    older = build_observation(observation_id="older", seq=1, server_received_mono_ns=MS)
    window.append(newer, now_mono_ns=2 * MS)
    window.append(older, now_mono_ns=2 * MS)

    assert window.prune(now_mono_ns=6 * MS + 1) == (older.observation_id,)
    assert window.get_observation(newer.observation_id) is newer
    assert window.get_observation(older.observation_id) is None


@pytest.mark.parametrize("recovered_status", ["ok", "degraded"])
def test_sensor_health_is_isolated_and_recovers(recovered_status: HealthStatus) -> None:
    window = make_window()
    assert not window.is_sensor_down("camera_0")
    down = build_observation(payload=HealthPayload(kind="health", status="down", detail_code="io"))
    window.append(down, now_mono_ns=0)
    assert window.is_sensor_down("camera_0")
    assert not window.is_sensor_down("camera_1")
    window.append(build_observation(observation_id="detection"), now_mono_ns=0)
    assert window.is_sensor_down("camera_0")
    window.prune(now_mono_ns=6 * MS)
    assert window.is_sensor_down("camera_0")

    recovery = build_observation(
        observation_id="recovery",
        seq=1,
        server_received_mono_ns=6 * MS,
        payload=HealthPayload(kind="health", status=recovered_status, detail_code="recovered"),
    )
    window.append(recovery, now_mono_ns=6 * MS)
    assert not window.is_sensor_down("camera_0")


@pytest.mark.parametrize("eviction", ["capacity", "time"])
def test_eviction_forgets_admission_id_but_preserves_sequence_history(eviction: str) -> None:
    window = make_window(window_max_items=1)
    profile = PolicyProfile(
        profile_id="test",
        profile_version=1,
        degraded_notice_text="감지가 제한됩니다.",
        params=window.params,
        scenarios=[make_scenario()],
    )
    admission = Admission(session_id="sess_0001", device_id="dev_0001", profile=profile)
    first = build_observation(observation_id="reused", seq=10)
    assert admission.admit(first, now_mono_ns=0) == "accepted"
    window.append(first, now_mono_ns=0)
    now = 0
    if eviction == "capacity":
        second = build_observation(observation_id="second", seq=11)
        assert admission.admit(second, now_mono_ns=now) == "accepted"
        evicted = window.append(second, now_mono_ns=now)
    else:
        now = 6 * MS
        evicted = window.prune(now_mono_ns=now)
    assert evicted == (first.observation_id,)
    for observation_id in evicted:
        admission.forget_observation(observation_id)
    assert window.get_observation(first.observation_id) is None

    old_sequence = build_observation(observation_id="reused", seq=10, server_received_mono_ns=now)
    assert admission.admit(old_sequence, now_mono_ns=now) == "out_of_order"
    valid = build_observation(observation_id="reused", seq=12, server_received_mono_ns=now)
    assert admission.admit(valid, now_mono_ns=now) == "accepted"
    window.append(valid, now_mono_ns=now)
    assert window.get_observation(valid.observation_id) is valid


@pytest.mark.parametrize("track_first", [False, True])
def test_frame_requires_all_kinds_and_returns_policy_order_once(track_first: bool) -> None:
    assembler = FrameAssembler(make_scenario(), make_window())
    detection, track = build_frame()
    first, second = (track, detection) if track_first else (detection, track)

    assert consume(assembler, first) is None
    assert consume(assembler, second) == (detection, track)
    assert assembler.add(first, now_mono_ns=0) is None
    assert assembler.add(second, now_mono_ns=0) is None
    for observation in (detection, track):
        assert assembler.window.get_observation(observation.observation_id) is observation


def test_scenarios_complete_independently_for_the_same_frame() -> None:
    window = make_window()
    moving = FrameAssembler(make_scenario(), window)
    obstacle = FrameAssembler(make_scenario(tracked=False), window)
    detection, track = build_frame()
    window.append(detection, now_mono_ns=0)

    assert moving.add(detection, now_mono_ns=0) is None
    assert obstacle.add(detection, now_mono_ns=0) == (detection,)
    window.append(track, now_mono_ns=0)
    assert moving.add(track, now_mono_ns=0) == (detection, track)
    assert obstacle.add(track, now_mono_ns=0) is None


@pytest.mark.parametrize("offset_ns", [0, 1])
def test_completion_rechecks_freshness_of_every_fragment(offset_ns: int) -> None:
    assembler = FrameAssembler(make_scenario(), make_window())
    detection, _ = build_frame()
    now = MS + offset_ns
    _, delayed_track = build_frame(server_received_mono_ns=now)

    assert consume(assembler, detection) is None
    result = consume(assembler, delayed_track, now_mono_ns=now)
    assert result == ((detection, delayed_track) if offset_ns == 0 else None)


def test_stale_completion_is_discarded_and_late_fragments_cannot_revive_it() -> None:
    assembler = FrameAssembler(make_scenario(), make_window())
    detection, _ = build_frame()
    consume(assembler, detection)
    now = MS + 1
    fresh_detection, fresh_track = build_frame(
        observation_ids=("fresh_detection", "fresh_track"),
        seq=1,
        server_received_mono_ns=now,
    )

    assert consume(assembler, fresh_track, now_mono_ns=now) is None
    assert assembler.window.get_observation(detection.observation_id) is detection
    assert consume(assembler, fresh_detection, now_mono_ns=now) is None


def test_late_duplicate_frame_with_new_observation_ids_does_not_complete_again() -> None:
    assembler = FrameAssembler(make_scenario(), make_window())
    detection, track = build_frame()
    consume(assembler, detection)
    assert consume(assembler, track) == (detection, track)
    late_fragments = build_frame(observation_ids=("late_detection", "late_track"), seq=1)

    for observation in late_fragments:
        assert consume(assembler, observation) is None


def test_different_frame_ids_never_complete_together() -> None:
    assembler = FrameAssembler(make_scenario(), make_window())
    detection_a, track_a = build_frame(frame_id="frame_a")
    _, track_b = build_frame(frame_id="frame_b", observation_ids=("unused", "track_b"))

    assert consume(assembler, detection_a) is None
    assert consume(assembler, track_b) is None
    assert consume(assembler, track_a) == (detection_a, track_a)


@pytest.mark.parametrize("unrelated", ["stream", "kind"])
def test_unrelated_stream_or_kind_does_not_enter_assembly(unrelated: str) -> None:
    assembler = FrameAssembler(make_scenario(), make_window())
    observation = (
        build_observation(observation_id="unrelated", stream_id="camera_1")
        if unrelated == "stream"
        else build_observation(
            observation_id="unrelated",
            payload=HealthPayload(kind="health", status="ok", detail_code="ok"),
        )
    )
    assert consume(assembler, observation) is None
    detection, track = build_frame()
    assert consume(assembler, track) is None
    assert consume(assembler, detection) == (detection, track)


def test_unindexed_observation_cannot_complete_a_frame() -> None:
    assembler = FrameAssembler(make_scenario(tracked=False), make_window())
    observation = build_observation()

    assert assembler.add(observation, now_mono_ns=0) is None
    assert consume(assembler, observation) == (observation,)


def test_capacity_evicted_fragment_cannot_be_used_as_evidence() -> None:
    assembler = FrameAssembler(make_scenario(), make_window(window_max_items=1))
    detection_a, track_a = build_frame(frame_id="frame_a")
    detection_b, _ = build_frame(frame_id="frame_b", observation_ids=("detection_b", "unused"))
    assert consume(assembler, detection_a) is None
    assert consume(assembler, detection_b) is None
    assert consume(assembler, track_a) is None
    assert assembler.window.get_observation(detection_a.observation_id) is None
    assert assembler.add(detection_a, now_mono_ns=0) is None

    replacement, _ = build_frame(frame_id="frame_a", observation_ids=("replacement", "unused"))
    assert consume(assembler, replacement) == (replacement, track_a)


def test_latest_kind_replaces_earlier_fragment_before_completion() -> None:
    assembler = FrameAssembler(make_scenario(), make_window())
    earlier, track = build_frame()
    newer, _ = build_frame(observation_ids=("newer", "unused"), seq=1)

    assert consume(assembler, earlier) is None
    assert consume(assembler, newer) is None
    assert consume(assembler, track) == (newer, track)


def test_incomplete_frame_expires_from_first_receipt_even_when_fragment_is_replaced() -> None:
    assembler = FrameAssembler(make_scenario(), make_window())
    first, _ = build_frame()
    consume(assembler, first)
    replacement, _ = build_frame(
        observation_ids=("replacement", "unused"), seq=1, server_received_mono_ns=4 * MS
    )
    consume(assembler, replacement, now_mono_ns=4 * MS)
    assembler.prune(now_mono_ns=5 * MS + 1)
    new_detection, new_track = build_frame(
        observation_ids=("new_detection", "new_track"),
        seq=2,
        server_received_mono_ns=5 * MS + 1,
    )

    assert assembler.window.get_observation(replacement.observation_id) is replacement
    assert consume(assembler, new_track, now_mono_ns=5 * MS + 1) is None
    assert consume(assembler, new_detection, now_mono_ns=5 * MS + 1) == (
        new_detection,
        new_track,
    )


def test_completed_frame_id_is_remembered_through_window_boundary() -> None:
    assembler = FrameAssembler(make_scenario(tracked=False), make_window())
    original = build_observation()
    assert consume(assembler, original) == (original,)
    boundary = build_observation(observation_id="boundary", seq=1, server_received_mono_ns=5 * MS)
    assert consume(assembler, boundary, now_mono_ns=5 * MS) is None
    after = build_observation(observation_id="after", seq=2, server_received_mono_ns=5 * MS + 1)
    assert consume(assembler, after, now_mono_ns=5 * MS + 1) == (after,)
