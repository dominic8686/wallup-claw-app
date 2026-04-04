"""Binary sensor platform for Hermes Intercom — device online status."""

from homeassistant.components.binary_sensor import (
    BinarySensorDeviceClass,
    BinarySensorEntity,
)
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddEntitiesCallback
from homeassistant.helpers.update_coordinator import CoordinatorEntity

from .const import DOMAIN


async def async_setup_entry(
    hass: HomeAssistant,
    entry: ConfigEntry,
    async_add_entities: AddEntitiesCallback,
) -> None:
    coordinator = hass.data[DOMAIN][entry.entry_id]
    entities = []
    for device_id in coordinator.data:
        entities.append(TabletOnlineSensor(coordinator, device_id, entry))
    async_add_entities(entities, True)


class TabletOnlineSensor(CoordinatorEntity, BinarySensorEntity):
    """Binary sensor: is the tablet online?"""

    _attr_device_class = BinarySensorDeviceClass.CONNECTIVITY

    def __init__(self, coordinator, device_id: str, entry: ConfigEntry) -> None:
        super().__init__(coordinator)
        self._device_id = device_id
        self._attr_unique_id = f"{entry.entry_id}_{device_id}_online"
        info = coordinator.data.get(device_id, {})
        self._attr_name = f"{info.get('display_name', device_id)} Online"
        self._attr_device_info = {
            "identifiers": {(DOMAIN, device_id)},
            "name": info.get("display_name", device_id),
            "manufacturer": "Hermes Intercom",
            "model": "Tablet",
            "suggested_area": info.get("room_location", ""),
        }

    @property
    def is_on(self) -> bool:
        info = self.coordinator.data.get(self._device_id, {})
        return info.get("status") == "online"
