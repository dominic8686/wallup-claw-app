"""Sensor platform for Hermes Intercom — call state and last activity."""

from datetime import datetime

from homeassistant.components.sensor import SensorEntity
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant, callback
from homeassistant.helpers.entity_platform import AddEntitiesCallback
from homeassistant.helpers.update_coordinator import CoordinatorEntity

from .const import DOMAIN


async def async_setup_entry(
    hass: HomeAssistant,
    entry: ConfigEntry,
    async_add_entities: AddEntitiesCallback,
) -> None:
    coordinator = hass.data[DOMAIN][entry.entry_id]
    known_device_ids: set[str] = set()

    # Global sensor (created once)
    async_add_entities([LastCallSensor(coordinator, entry)])

    @callback
    def _async_add_new_devices() -> None:
        new_entities = []
        for device_id in coordinator.data or {}:
            if device_id not in known_device_ids:
                known_device_ids.add(device_id)
                new_entities.append(TabletCallStateSensor(coordinator, device_id, entry))
                new_entities.append(TabletLastActivitySensor(coordinator, device_id, entry))
        if new_entities:
            async_add_entities(new_entities)

    _async_add_new_devices()
    entry.async_on_unload(coordinator.async_add_listener(_async_add_new_devices))


class TabletCallStateSensor(CoordinatorEntity, SensorEntity):
    """Sensor: tablet call state (idle, ringing, in_call, do_not_disturb)."""

    _attr_icon = "mdi:phone"

    def __init__(self, coordinator, device_id: str, entry: ConfigEntry) -> None:
        super().__init__(coordinator)
        self._device_id = device_id
        self._attr_unique_id = f"{entry.entry_id}_{device_id}_call_state"
        info = coordinator.data.get(device_id, {})
        self._attr_name = f"{info.get('display_name', device_id)} Call State"
        self._attr_device_info = {
            "identifiers": {(DOMAIN, device_id)},
        }

    @property
    def native_value(self) -> str:
        info = self.coordinator.data.get(self._device_id, {})
        return info.get("call_state", "unknown")

    @property
    def extra_state_attributes(self) -> dict:
        info = self.coordinator.data.get(self._device_id, {})
        return {
            "device_id": self._device_id,
            "status": info.get("status", "offline"),
            "room_location": info.get("room_location", ""),
        }


class TabletLastActivitySensor(CoordinatorEntity, SensorEntity):
    """Sensor: last activity timestamp."""

    _attr_icon = "mdi:clock-outline"

    def __init__(self, coordinator, device_id: str, entry: ConfigEntry) -> None:
        super().__init__(coordinator)
        self._device_id = device_id
        self._attr_unique_id = f"{entry.entry_id}_{device_id}_last_activity"
        info = coordinator.data.get(device_id, {})
        self._attr_name = f"{info.get('display_name', device_id)} Last Activity"
        self._attr_device_info = {
            "identifiers": {(DOMAIN, device_id)},
        }

    @property
    def native_value(self) -> str | None:
        info = self.coordinator.data.get(self._device_id, {})
        last_seen = info.get("last_seen", 0)
        if last_seen:
            return datetime.fromtimestamp(last_seen).isoformat()
        return None


class LastCallSensor(CoordinatorEntity, SensorEntity):
    """Sensor: most recent intercom call details."""

    _attr_icon = "mdi:phone-log"

    def __init__(self, coordinator, entry: ConfigEntry) -> None:
        super().__init__(coordinator)
        self._attr_unique_id = f"{entry.entry_id}_last_call"
        self._attr_name = "Hermes Intercom Last Call"

    @property
    def native_value(self) -> str | None:
        call = self.coordinator.last_call
        if call:
            return f"{call.get('from', '?')} → {call.get('to', '?')}"
        return "No calls"

    @property
    def extra_state_attributes(self) -> dict:
        call = self.coordinator.last_call
        if not call:
            return {"history_count": 0}
        return {
            "call_id": call.get("call_id"),
            "from": call.get("from"),
            "to": call.get("to"),
            "started_at": call.get("started_at"),
            "status": call.get("status"),
            "duration": call.get("duration"),
            "history_count": len(self.coordinator.call_history),
        }
