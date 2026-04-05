"""Select platform for Hermes Intercom — wake word model and call mode."""

import aiohttp
import logging

from homeassistant.components.select import SelectEntity
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant, callback
from homeassistant.helpers.entity_platform import AddEntitiesCallback
from homeassistant.helpers.update_coordinator import CoordinatorEntity

from .const import DOMAIN

_LOGGER = logging.getLogger(__name__)

WAKEWORD_OPTIONS = {
    "jarvis_v2": "Hey Jarvis",
    "alexa": "Alexa",
    "hey_mycroft": "Hey Mycroft",
}

CALL_MODE_OPTIONS = {
    "manual": "Manual",
    "wakeword": "Wake Word",
}


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
                new_entities.append(TabletWakeWordModelSelect(coordinator, device_id, entry))
                new_entities.append(TabletCallModeSelect(coordinator, device_id, entry))
        if new_entities:
            async_add_entities(new_entities)

    _async_add_new_devices()
    entry.async_on_unload(coordinator.async_add_listener(_async_add_new_devices))


class TabletConfigSelect(CoordinatorEntity, SelectEntity):
    """Base class for config-push select entities."""

    def __init__(self, coordinator, device_id: str, entry: ConfigEntry,
                 config_key: str, label: str, icon: str,
                 options_map: dict[str, str], default: str) -> None:
        super().__init__(coordinator)
        self._device_id = device_id
        self._config_key = config_key
        self._options_map = options_map
        self._reverse_map = {v: k for k, v in options_map.items()}
        self._current = default
        self._attr_unique_id = f"{entry.entry_id}_{device_id}_{config_key}"
        info = coordinator.data.get(device_id, {})
        self._attr_name = f"{info.get('display_name', device_id)} {label}"
        self._attr_icon = icon
        self._attr_options = list(options_map.values())
        self._attr_device_info = {
            "identifiers": {(DOMAIN, device_id)},
        }

    @property
    def current_option(self) -> str | None:
        return self._options_map.get(self._current, self._attr_options[0])

    async def async_select_option(self, option: str) -> None:
        key = self._reverse_map.get(option)
        if not key:
            return
        self._current = key

        session = self.coordinator._session
        if session is None or session.closed:
            session = aiohttp.ClientSession()
            self.coordinator._session = session

        try:
            async with session.post(
                f"{self.coordinator.url}/configure",
                json={"device_id": self._device_id, "settings": {self._config_key: key}},
                timeout=aiohttp.ClientTimeout(total=10),
            ) as resp:
                result = await resp.json()
                if result.get("ok"):
                    _LOGGER.info("Config pushed to %s: %s = %s", self._device_id, self._config_key, key)
        except Exception as err:
            _LOGGER.error("Config push error: %s", err)

        self.async_write_ha_state()


class TabletWakeWordModelSelect(TabletConfigSelect):
    """Select: wake word model (Hey Jarvis / Alexa / Hey Mycroft)."""

    def __init__(self, coordinator, device_id: str, entry: ConfigEntry) -> None:
        super().__init__(
            coordinator, device_id, entry,
            config_key="wakeword_model",
            label="Wake Word Model",
            icon="mdi:microphone-message",
            options_map=WAKEWORD_OPTIONS,
            default="jarvis_v2",
        )


class TabletCallModeSelect(TabletConfigSelect):
    """Select: call mode (manual / wakeword)."""

    def __init__(self, coordinator, device_id: str, entry: ConfigEntry) -> None:
        super().__init__(
            coordinator, device_id, entry,
            config_key="call_mode",
            label="Call Mode",
            icon="mdi:phone-settings",
            options_map=CALL_MODE_OPTIONS,
            default="wakeword",
        )
