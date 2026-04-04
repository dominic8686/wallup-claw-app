"""Text platform for Hermes Intercom — configurable tablet settings."""

import aiohttp
import logging

from homeassistant.components.text import TextEntity
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
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
    entities = []
    for device_id in coordinator.data:
        entities.append(TabletDisplayNameText(coordinator, device_id, entry))
        entities.append(TabletRoomLocationText(coordinator, device_id, entry))
    async_add_entities(entities, True)


class TabletDisplayNameText(CoordinatorEntity, TextEntity):
    """Text entity: tablet display name (pushed to device via /configure)."""

    _attr_icon = "mdi:rename-box"
    _attr_native_max = 64

    def __init__(self, coordinator, device_id: str, entry: ConfigEntry) -> None:
        super().__init__(coordinator)
        self._device_id = device_id
        self._attr_unique_id = f"{entry.entry_id}_{device_id}_display_name"
        info = coordinator.data.get(device_id, {})
        self._attr_name = f"{info.get('display_name', device_id)} Display Name"
        self._attr_device_info = {
            "identifiers": {(DOMAIN, device_id)},
            "name": info.get("display_name", device_id),
            "manufacturer": "Hermes Intercom",
            "model": "Tablet",
        }

    @property
    def native_value(self) -> str:
        info = self.coordinator.data.get(self._device_id, {})
        return info.get("display_name", self._device_id)

    async def async_set_value(self, value: str) -> None:
        """Push new display name to the tablet via /configure."""
        await self._push_config({"display_name": value})

    async def _push_config(self, settings: dict) -> None:
        session = self.coordinator._session
        if session is None or session.closed:
            session = aiohttp.ClientSession()
            self.coordinator._session = session

        try:
            async with session.post(
                f"{self.coordinator.url}/configure",
                json={"device_id": self._device_id, "settings": settings},
                timeout=aiohttp.ClientTimeout(total=10),
            ) as resp:
                result = await resp.json()
                if result.get("ok"):
                    _LOGGER.info("Config pushed to %s: %s", self._device_id, settings)
                else:
                    _LOGGER.warning("Config push failed: %s", result)
        except Exception as err:
            _LOGGER.error("Config push error: %s", err)

        await self.coordinator.async_request_refresh()


class TabletRoomLocationText(CoordinatorEntity, TextEntity):
    """Text entity: tablet room location (pushed to device via /configure)."""

    _attr_icon = "mdi:map-marker"
    _attr_native_max = 64

    def __init__(self, coordinator, device_id: str, entry: ConfigEntry) -> None:
        super().__init__(coordinator)
        self._device_id = device_id
        self._attr_unique_id = f"{entry.entry_id}_{device_id}_room_location"
        info = coordinator.data.get(device_id, {})
        self._attr_name = f"{info.get('display_name', device_id)} Room Location"
        self._attr_device_info = {
            "identifiers": {(DOMAIN, device_id)},
        }

    @property
    def native_value(self) -> str:
        info = self.coordinator.data.get(self._device_id, {})
        return info.get("room_location", "")

    async def async_set_value(self, value: str) -> None:
        """Push new room location to the tablet via /configure."""
        session = self.coordinator._session
        if session is None or session.closed:
            session = aiohttp.ClientSession()
            self.coordinator._session = session

        try:
            async with session.post(
                f"{self.coordinator.url}/configure",
                json={"device_id": self._device_id, "settings": {"room_location": value}},
                timeout=aiohttp.ClientTimeout(total=10),
            ) as resp:
                result = await resp.json()
                if result.get("ok"):
                    _LOGGER.info("Room location pushed to %s: %s", self._device_id, value)
        except Exception as err:
            _LOGGER.error("Config push error: %s", err)

        await self.coordinator.async_request_refresh()
