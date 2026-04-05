"""Button platform for Hermes Intercom — start conversation trigger."""

import logging

from homeassistant.components.button import ButtonEntity
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
                new_entities.append(TabletStartConversationButton(coordinator, device_id, entry))
        if new_entities:
            async_add_entities(new_entities)

    _async_add_new_devices()
    entry.async_on_unload(coordinator.async_add_listener(_async_add_new_devices))


class TabletStartConversationButton(CoordinatorEntity, ButtonEntity):
    """Button: trigger voice conversation on a tablet."""

    _attr_icon = "mdi:microphone-message"

    def __init__(self, coordinator, device_id: str, entry: ConfigEntry) -> None:
        super().__init__(coordinator)
        self._device_id = device_id
        self._attr_unique_id = f"{entry.entry_id}_{device_id}_start_conversation"
        info = coordinator.data.get(device_id, {})
        self._attr_name = f"{info.get('display_name', device_id)} Start Conversation"
        self._attr_device_info = {
            "identifiers": {(DOMAIN, device_id)},
        }

    async def async_press(self) -> None:
        """Send start_conversation signal to the tablet."""
        try:
            await self.coordinator._post_signal({
                "type": "start_conversation",
                "from": "homeassistant",
                "to": self._device_id,
            })
            _LOGGER.info("Start conversation sent to %s", self._device_id)
        except Exception as err:
            _LOGGER.error("Start conversation failed for %s: %s", self._device_id, err)
