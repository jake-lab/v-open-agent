"""Observation retention and per-scenario frame assembly (SPEC 4.3, 4.4).

The caller prunes before Admission, appends accepted observations only, and passes
every evicted ID to Admission.forget_observation(). Assemblers are pruned after
window updates, including ticks. Queries never prune or advance engine time.
"""

from collections import deque
from dataclasses import dataclass, field

from voa.contracts.enums import SourceKind
from voa.contracts.observation import (
    DetectionPayload,
    HealthPayload,
    Observation,
    TrackPayload,
)
from voa.engine.admission import AdmissionKey
from voa.engine.profile import PolicyParams, ScenarioProfile


@dataclass(frozen=True)
class ObservationWindow:
    """Retain accepted observations, with limits applied per stream and kind."""

    params: PolicyParams
    _queues: dict[AdmissionKey, deque[Observation]] = field(
        default_factory=dict, init=False, repr=False
    )
    _index: dict[str, Observation] = field(default_factory=dict, init=False, repr=False)
    _sensor_down: dict[str, bool] = field(default_factory=dict, init=False, repr=False)

    def append(self, observation: Observation, *, now_mono_ns: int) -> tuple[str, ...]:
        """Store an accepted observation and return all time/capacity evictions."""
        evicted = list(self.prune(now_mono_ns=now_mono_ns))
        key = (observation.stream_id, observation.source_kind)
        queue = self._queues.setdefault(key, deque())
        queue.append(observation)
        self._index[observation.observation_id] = observation
        while len(queue) > self.params.window_max_items:
            removed = queue.popleft()
            del self._index[removed.observation_id]
            evicted.append(removed.observation_id)

        if isinstance(observation.payload, HealthPayload):
            self._sensor_down[observation.stream_id] = observation.payload.status == "down"
        return tuple(evicted)

    def prune(self, *, now_mono_ns: int) -> tuple[str, ...]:
        """Expire observations across all keys, synchronizing the evidence index."""
        evicted: list[str] = []
        window_ns = self.params.window_ms * 1_000_000
        for key, queue in tuple(self._queues.items()):
            retained: deque[Observation] = deque()
            for observation in queue:
                if now_mono_ns - observation.server_received_mono_ns > window_ns:
                    del self._index[observation.observation_id]
                    evicted.append(observation.observation_id)
                else:
                    retained.append(observation)
            if retained:
                self._queues[key] = retained
            else:
                del self._queues[key]
        return tuple(evicted)

    def get_observation(self, observation_id: str) -> Observation | None:
        """Read the evidence index without changing retention."""
        return self._index.get(observation_id)

    def is_fresh(self, observation_id: str, *, now_mono_ns: int) -> bool:
        """Missing evidence is not fresh; the staleness boundary is inclusive."""
        observation = self.get_observation(observation_id)
        return (
            observation is not None
            and now_mono_ns - observation.server_received_mono_ns
            <= self.params.staleness_ms * 1_000_000
        )

    def is_sensor_down(self, stream_id: str) -> bool:
        """The latest accepted health report persists until a newer report."""
        return self._sensor_down.get(stream_id, False)


@dataclass
class _PendingFrame:
    first_received_mono_ns: int
    observations: dict[SourceKind, Observation] = field(default_factory=dict)


@dataclass(frozen=True)
class FrameAssembler:
    """Emit each complete, fresh frame once, independently for one scenario."""

    scenario: ScenarioProfile
    window: ObservationWindow
    _pending: dict[str, _PendingFrame] = field(default_factory=dict, init=False, repr=False)
    _completed: dict[str, int] = field(default_factory=dict, init=False, repr=False)

    def add(self, observation: Observation, *, now_mono_ns: int) -> tuple[Observation, ...] | None:
        """Consume an indexed observation; return required kinds in policy order."""
        self.prune(now_mono_ns=now_mono_ns)
        payload = observation.payload
        if (
            observation.stream_id not in self.scenario.required_streams
            or observation.source_kind not in self.scenario.required_kinds
            or not isinstance(payload, DetectionPayload | TrackPayload)
            or self.window.get_observation(observation.observation_id) is not observation
        ):
            return None

        frame_id = payload.frame_id
        if frame_id in self._completed:
            return None
        frame = self._pending.setdefault(
            frame_id, _PendingFrame(observation.server_received_mono_ns)
        )
        frame.first_received_mono_ns = min(
            frame.first_received_mono_ns, observation.server_received_mono_ns
        )
        frame.observations[observation.source_kind] = observation
        if not all(kind in frame.observations for kind in self.scenario.required_kinds):
            return None

        observations = tuple(frame.observations[kind] for kind in self.scenario.required_kinds)
        del self._pending[frame_id]
        self._completed[frame_id] = now_mono_ns
        if not all(
            self.window.is_fresh(item.observation_id, now_mono_ns=now_mono_ns)
            for item in observations
        ):
            return None
        return observations

    def prune(self, *, now_mono_ns: int) -> None:
        """Expire assembly buffers and completed IDs; remove evicted evidence."""
        window_ns = self.window.params.window_ms * 1_000_000
        for frame_id, completed_at in tuple(self._completed.items()):
            if now_mono_ns - completed_at > window_ns:
                del self._completed[frame_id]
        for frame_id, frame in tuple(self._pending.items()):
            if now_mono_ns - frame.first_received_mono_ns > window_ns:
                del self._pending[frame_id]
                continue
            frame.observations = {
                kind: observation
                for kind, observation in frame.observations.items()
                if self.window.get_observation(observation.observation_id) is observation
            }
            if not frame.observations:
                del self._pending[frame_id]
