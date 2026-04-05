"""Number platform for Hermes Intercom — wake word sensitivity."""

import aiohttp
import logging

from homeassistant.components.number import NumberEntity, NumberMode
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant, callback
from homeassistant.helpers.entity_platform import AddEntitiesCallback
from homeassistant.helpers.update_coordinator import CoordinatorEntity

from .const import DOMAIN

_LOGGER = logging.getLogger(__name__)


async def async_setup_entry(
    hass: HomeAssistant,
    entry: ConfigEntry,
    async_add_entities: AddEntitiesCallback,
) -> None:
    coordinator = hass.data[DOMAIN][entry.entry_id]
    known_device_ids: set[str] = set()

    @callback
    def _async_add_new_devices() -> None:
        new_entities = []
        for device_id in coordinator.data or {}:
            if device_id not in known_device_ids:
                known_device_ids.add(device_id)
                new_entities.append(TabletWakeWordSensitivityNumber(coordinator, device_id, entry))
        if new_entities:
            async_add_entities(new_entities)

    _async_add_new_devices()
    entry.async_on_unload(coordinator.async_add_listener(_async_add_new_devices))


class TabletWakeWordSensitivityNumber(CoordinatorEntity, NumberEntity):
    """Number: wake word detection sensitivity (0.1–0.9)."""

    _attr_icon = "mdi:tune"
    _attr_native_min_value = 0.1
    _attr_native_max_value = 0.9
    _attr_native_step = 0.1
    _attr_mode = NumberMode.SLIDER

    def __init__(self, coordinator, device_id: str, entry: ConfigEntry) -> None:
        super().__init__(coordinator)
        self._device_id = device_id
        self._value = 0.5
        self._attr_unique_id = f"{entry.entry_id}_{device_id}_wakeword_sensitivity"
        info = coordinator.data.get(device_id, {})
        self._attr_name = f"{info.get('display_name', device_id)} Wake Word Sensitivity"
        self._attr_device_info = {
            "identifiers": {(DOMAIN, device_id)},
        }

    @property
    def native_value(self) -> float:
        return self._value

    async def async_set_native_value(self, value: float) -> None:
        self._value = round(value, 1)

        session = self.coordinator._session
        if session is None or session.closed:
            session = aiohttp.ClientSession()
            self.coordinator._session = session

        try:
            async with session.post(
                f"{self.coordinator.url}/configure",
                json={"device_id": self._device_id, "settings": {"wakeword_sensitivity": self._value}},
                timeout=aiohttp.ClientTimeout(total=10),
            ) as resp:
                result = await resp.json()
                if result.get("ok"):
                    _LOGGER.info("Sensitivity pushed to %s: %s", self._device_id, self._value)
        except Exception as err:
            _LOGGER.error("Config push error: %s", err)

        self.async_write_ha_state()
