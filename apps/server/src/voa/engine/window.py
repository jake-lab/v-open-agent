"""Observation window, freshness, sensor health, and frame assembly (SPEC 4.3-4.4)."""

from __future__ import annotations

from collections import deque
from dataclasses import dataclass, field

from voa.contracts.enums import SourceKind
from voa.contracts.observation import (
    DetectionPayload,
    HealthPayload,
    Observation,
    TrackPayload,
)
from voa.engine.profile import PolicyParams, PolicyProfile

WindowKey = tuple[str, SourceKind]


@dataclass(frozen=True)
class AssembledFrame:
    """A fresh, complete frame for one scenario, ordered by required kind."""

    scenario_id: str
    frame_id: str
    observations: tuple[Observation, ...]


class ObservationWindow:
    """Retain accepted observations and expose read-only evidence lookup."""

    def __init__(self, params: PolicyParams) -> None:
        self._params = params
        self._by_key: dict[WindowKey, deque[Observation]] = {}
        self._by_id: dict[str, Observation] = {}
        self._sensor_down: dict[str, bool] = {}

    def add(self, observation: Observation, *, now_mono_ns: int) -> tuple[str, ...]:
        """Store one accepted observation and return every ID evicted in this call."""
        evicted_ids = list(self.expire(now_mono_ns=now_mono_ns))
        key = (observation.stream_id, observation.source_kind)
        observations = self._by_key.setdefault(key, deque())
        observations.append(observation)
        self._by_id[observation.observation_id] = observation

        if isinstance(observation.payload, HealthPayload):
            self._sensor_down[observation.stream_id] = observation.payload.status == "down"

        while len(observations) > self._params.window_max_items:
            evicted = observations.popleft()
            self._by_id.pop(evicted.observation_id, None)
            evicted_ids.append(evicted.observation_id)

        return tuple(evicted_ids)

    def expire(self, *, now_mono_ns: int) -> tuple[str, ...]:
        """Evict observations older than window_ms and return their IDs."""
        window_ns = self._params.window_ms * 1_000_000
        evicted_ids: list[str] = []
        empty_keys: list[WindowKey] = []

        for key, observations in self._by_key.items():
            while (
                observations and now_mono_ns - observations[0].server_received_mono_ns > window_ns
            ):
                evicted = observations.popleft()
                self._by_id.pop(evicted.observation_id, None)
                evicted_ids.append(evicted.observation_id)
            if not observations:
                empty_keys.append(key)

        for key in empty_keys:
            del self._by_key[key]

        return tuple(evicted_ids)

    def get(self, observation_id: str) -> Observation | None:
        """Return retained evidence without changing window state."""
        return self._by_id.get(observation_id)

    def is_fresh(self, observation_id: str, *, now_mono_ns: int) -> bool:
        """Return whether retained evidence is at the inclusive freshness boundary."""
        observation = self.get(observation_id)
        if observation is None:
            return False
        staleness_ns = self._params.staleness_ms * 1_000_000
        return now_mono_ns - observation.server_received_mono_ns <= staleness_ns

    def is_sensor_down(self, stream_id: str) -> bool:
        """Return the last reported health state; streams default to available."""
        return self._sensor_down.get(stream_id, False)


@dataclass
class _PendingFrame:
    oldest_received_mono_ns: int
    observations: dict[SourceKind, Observation] = field(default_factory=dict)


class FrameAssembler:
    """Assemble accepted vision observations independently for each scenario."""

    def __init__(self, profile: PolicyProfile) -> None:
        self._profile = profile
        self._pending: dict[str, dict[str, _PendingFrame]] = {
            scenario.id: {} for scenario in profile.scenarios
        }
        self._completed: dict[str, dict[str, int]] = {
            scenario.id: {} for scenario in profile.scenarios
        }

    def add(self, observation: Observation, *, now_mono_ns: int) -> tuple[AssembledFrame, ...]:
        """Add one accepted observation and return newly completed fresh frames."""
        self.expire(now_mono_ns=now_mono_ns)
        payload = observation.payload
        if not isinstance(payload, DetectionPayload | TrackPayload):
            return ()

        completed_frames: list[AssembledFrame] = []
        for scenario in self._profile.scenarios:
            if (
                observation.stream_id not in scenario.required_streams
                or observation.source_kind not in scenario.required_kinds
            ):
                continue

            frame_id = payload.frame_id
            if frame_id in self._completed[scenario.id]:
                continue

            pending = self._pending[scenario.id].get(frame_id)
            if pending is None:
                pending = _PendingFrame(observation.server_received_mono_ns)
                self._pending[scenario.id][frame_id] = pending
            else:
                pending.oldest_received_mono_ns = min(
                    pending.oldest_received_mono_ns,
                    observation.server_received_mono_ns,
                )
            pending.observations.setdefault(observation.source_kind, observation)

            if not all(kind in pending.observations for kind in scenario.required_kinds):
                continue

            observations = tuple(pending.observations[kind] for kind in scenario.required_kinds)
            del self._pending[scenario.id][frame_id]
            self._completed[scenario.id][frame_id] = now_mono_ns
            if all(self._is_fresh(item, now_mono_ns=now_mono_ns) for item in observations):
                completed_frames.append(
                    AssembledFrame(
                        scenario_id=scenario.id,
                        frame_id=frame_id,
                        observations=observations,
                    )
                )

        return tuple(completed_frames)

    def expire(self, *, now_mono_ns: int) -> None:
        """Forget incomplete and completed frame IDs after window_ms."""
        window_ns = self._profile.params.window_ms * 1_000_000
        for scenario in self._profile.scenarios:
            pending = self._pending[scenario.id]
            expired_pending = [
                frame_id
                for frame_id, frame in pending.items()
                if now_mono_ns - frame.oldest_received_mono_ns > window_ns
            ]
            for frame_id in expired_pending:
                del pending[frame_id]

            completed = self._completed[scenario.id]
            expired_completed = [
                frame_id
                for frame_id, completed_at in completed.items()
                if now_mono_ns - completed_at > window_ns
            ]
            for frame_id in expired_completed:
                del completed[frame_id]

    def _is_fresh(self, observation: Observation, *, now_mono_ns: int) -> bool:
        staleness_ns = self._profile.params.staleness_ms * 1_000_000
        return now_mono_ns - observation.server_received_mono_ns <= staleness_ns
