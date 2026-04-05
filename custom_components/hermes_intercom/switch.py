"""Switch platform for Hermes Intercom — Do Not Disturb toggle."""

import aiohttp

from homeassistant.components.switch import SwitchEntity
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

    @callback
    def _async_add_new_devices() -> None:
        new_entities = []
        for device_id in coordinator.data or {}:
            if device_id not in known_device_ids:
                known_device_ids.add(device_id)
                new_entities.append(TabletDNDSwitch(coordinator, device_id, entry))
        if new_entities:
            async_add_entities(new_entities)

    _async_add_new_devices()
    entry.async_on_unload(coordinator.async_add_listener(_async_add_new_devices))


class TabletDNDSwitch(CoordinatorEntity, SwitchEntity):
    """Switch: toggle Do Not Disturb on a tablet."""

    _attr_icon = "mdi:bell-off"

    def __init__(self, coordinator, device_id: str, entry: ConfigEntry) -> None:
        super().__init__(coordinator)
        self._device_id = device_id
        self._attr_unique_id = f"{entry.entry_id}_{device_id}_dnd"
        info = coordinator.data.get(device_id, {})
        self._attr_name = f"{info.get('display_name', device_id)} Do Not Disturb"
        self._attr_device_info = {
            "identifiers": {(DOMAIN, device_id)},
        }

    @property
    def is_on(self) -> bool:
        info = self.coordinator.data.get(self._device_id, {})
        return info.get("call_state") == "do_not_disturb"

    async def async_turn_on(self, **kwargs) -> None:
        await self._set_dnd(True)

    async def async_turn_off(self, **kwargs) -> None:
        await self._set_dnd(False)

    async def _set_dnd(self, enabled: bool) -> None:
        session = self.coordinator._session
        if session is None or session.closed:
            session = aiohttp.ClientSession()
            self.coordinator._session = session

        async with session.post(
            f"{self.coordinator.url}/heartbeat",
            json={
                "device_id": self._device_id,
                "call_state": "do_not_disturb" if enabled else "idle",
            },
            timeout=aiohttp.ClientTimeout(total=5),
        ) as resp:
            pass

        await self.coordinator.async_request_refresh()
