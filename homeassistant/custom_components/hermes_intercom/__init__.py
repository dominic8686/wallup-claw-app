"""Hermes Intercom integration for Home Assistant.

Exposes LiveKit-connected tablets as HA devices with call/broadcast services.
"""

import asyncio
import json
import logging
from datetime import timedelta

import aiohttp
import voluptuous as vol

from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant, ServiceCall
from homeassistant.helpers import device_registry as dr
from homeassistant.helpers.update_coordinator import DataUpdateCoordinator, UpdateFailed
from homeassistant.util import dt as dt_util
import homeassistant.helpers.config_validation as cv

from .const import (
    DOMAIN,
    CONF_TOKEN_SERVER_URL,
    DEFAULT_SCAN_INTERVAL,
    EVENT_CALL_STARTED,
    EVENT_CALL_ENDED,
    EVENT_DEVICE_ONLINE,
    EVENT_DEVICE_OFFLINE,
    PLATFORMS,
    SERVICE_CALL,
    SERVICE_BROADCAST,
    SERVICE_HANGUP,
    SERVICE_SET_DND,
    SERVICE_CONFIGURE_DEVICE,
)

_LOGGER = logging.getLogger(__name__)


# ---------------------------------------------------------------------------
# Coordinator: polls /devices from token server
# ---------------------------------------------------------------------------

class HermesIntercomCoordinator(DataUpdateCoordinator):
    """Fetch device list from the token server periodically."""

    def __init__(self, hass: HomeAssistant, url: str) -> None:
        super().__init__(
            hass,
            _LOGGER,
            name=DOMAIN,
            update_interval=timedelta(seconds=DEFAULT_SCAN_INTERVAL),
        )
        self.url = url
        self._session: aiohttp.ClientSession | None = None
        self._prev_statuses: dict[str, str] = {}  # device_id -> previous status
        self.call_history: list[dict] = []  # Most recent calls (max 50)
        self.last_call: dict | None = None  # Most recent call details

    async def _async_update_data(self) -> dict:
        """Fetch devices from token server."""
        if self._session is None or self._session.closed:
            self._session = aiohttp.ClientSession()

        try:
            async with self._session.get(
                f"{self.url}/devices",
                timeout=aiohttp.ClientTimeout(total=10),
            ) as resp:
                if resp.status != 200:
                    raise UpdateFailed(f"Token server returned {resp.status}")
                data = await resp.json()
        except Exception as err:
            raise UpdateFailed(f"Error fetching devices: {err}") from err

        devices = {d["device_id"]: d for d in data.get("devices", [])}

        # Fire online/offline events on status changes
        for device_id, info in devices.items():
            prev = self._prev_statuses.get(device_id)
            current = info.get("status", "offline")
            if prev and prev != current:
                if current == "online":
                    self.hass.bus.async_fire(EVENT_DEVICE_ONLINE, {"device_id": device_id})
                elif current == "offline":
                    self.hass.bus.async_fire(EVENT_DEVICE_OFFLINE, {"device_id": device_id})
            self._prev_statuses[device_id] = current

        return devices

    async def async_shutdown(self) -> None:
        if self._session and not self._session.closed:
            await self._session.close()

    async def _post_signal(self, data: dict) -> dict:
        """Post a signal to the token server."""
        if self._session is None or self._session.closed:
            self._session = aiohttp.ClientSession()

        async with self._session.post(
            f"{self.url}/signal",
            json=data,
            timeout=aiohttp.ClientTimeout(total=10),
        ) as resp:
            return await resp.json()


# ---------------------------------------------------------------------------
# Setup / Unload
# ---------------------------------------------------------------------------

async def async_setup_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    """Set up Hermes Intercom from a config entry."""
    url = entry.data[CONF_TOKEN_SERVER_URL]

    coordinator = HermesIntercomCoordinator(hass, url)
    await coordinator.async_config_entry_first_refresh()

    hass.data.setdefault(DOMAIN, {})
    hass.data[DOMAIN][entry.entry_id] = coordinator

    # Register services
    _register_services(hass, coordinator)

    # Forward to entity platforms
    await hass.config_entries.async_forward_entry_setups(entry, PLATFORMS)

    return True


async def async_unload_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    """Unload a config entry."""
    coordinator: HermesIntercomCoordinator = hass.data[DOMAIN].pop(entry.entry_id)
    await coordinator.async_shutdown()

    unload_ok = await hass.config_entries.async_unload_platforms(entry, PLATFORMS)
    return unload_ok


async def async_remove_config_entry_device(
    hass: HomeAssistant,
    config_entry: ConfigEntry,
    device_entry: dr.DeviceEntry,
) -> bool:
    """Allow removal of a device that is no longer reported by the token server."""
    coordinator: HermesIntercomCoordinator = hass.data[DOMAIN][config_entry.entry_id]
    # Extract the device_id from the device registry identifiers
    device_ids = {
        identifier[1]
        for identifier in device_entry.identifiers
        if identifier[0] == DOMAIN
    }
    # Allow deletion only if the device is NOT currently in the coordinator data
    # (i.e. the token server no longer reports it)
    for device_id in device_ids:
        if device_id in (coordinator.data or {}):
            return False  # Device is still active — don't allow deletion
    return True


# ---------------------------------------------------------------------------
# Services
# ---------------------------------------------------------------------------

def _register_services(hass: HomeAssistant, coordinator: HermesIntercomCoordinator):
    """Register hermes_intercom.* services."""

    async def handle_call(call: ServiceCall) -> None:
        """Handle hermes_intercom.call service."""
        target = call.data["target"]
        source = call.data.get("source", "homeassistant")

        result = await coordinator._post_signal({
            "type": "call_request",
            "from": source,
            "to": target,
        })

        if result.get("ok"):
            call_record = {
                "call_id": result.get("call_id"),
                "from": source,
                "to": target,
                "started_at": dt_util.utcnow().isoformat(),
                "status": "ringing",
                "duration": None,
            }
            coordinator.last_call = call_record
            coordinator.call_history.append(call_record)
            if len(coordinator.call_history) > 50:
                coordinator.call_history.pop(0)
            hass.bus.async_fire(EVENT_CALL_STARTED, {
                "call_id": result.get("call_id"),
                "from": source,
                "to": target,
            })
            _LOGGER.info("Call initiated: %s -> %s (%s)", source, target, result.get("call_id"))
        else:
            _LOGGER.warning("Call failed: %s", result.get("error"))

    async def handle_broadcast(call: ServiceCall) -> None:
        """Handle hermes_intercom.broadcast service."""
        message = call.data["message"]
        targets = call.data.get("targets", "all")

        if targets == "all":
            devices = coordinator.data or {}
            target_ids = [
                did for did, info in devices.items()
                if info.get("status") == "online"
            ]
        else:
            target_ids = [t.strip() for t in targets.split(",")]

        for target in target_ids:
            try:
                await coordinator._post_signal({
                    "type": "announcement",
                    "from": "homeassistant",
                    "to": target,
                    "message": message,
                })
            except Exception as err:
                _LOGGER.warning("Broadcast to %s failed: %s", target, err)

        _LOGGER.info("Broadcast sent to %d devices: %s", len(target_ids), message[:50])

    async def handle_hangup(call: ServiceCall) -> None:
        """Handle hermes_intercom.hangup service."""
        call_id = call.data.get("call_id", "")
        source = call.data.get("source", "homeassistant")
        target = call.data.get("target", "")

        if call_id and target:
            result = await coordinator._post_signal({
                "type": "call_hangup",
                "from": source,
                "to": target,
                "call_id": call_id,
            })
            if result.get("ok"):
                # Update last_call with ended status and duration
                if coordinator.last_call and coordinator.last_call.get("call_id") == call_id:
                    started = coordinator.last_call.get("started_at", "")
                    coordinator.last_call["status"] = "ended"
                    coordinator.last_call["ended_at"] = dt_util.utcnow().isoformat()
                    if started:
                        try:
                            start_dt = dt_util.parse_datetime(started)
                            if start_dt:
                                dur = (dt_util.utcnow() - start_dt).total_seconds()
                                coordinator.last_call["duration"] = int(dur)
                        except Exception:
                            pass
                    coordinator.async_set_updated_data(coordinator.data)
                hass.bus.async_fire(EVENT_CALL_ENDED, {
                    "call_id": call_id,
                    "from": source,
                    "to": target,
                })

    async def handle_set_dnd(call: ServiceCall) -> None:
        """Handle hermes_intercom.set_dnd service."""
        target = call.data["target"]
        enabled = call.data.get("enabled", True)

        # Update via heartbeat with call_state
        if coordinator._session is None or coordinator._session.closed:
            coordinator._session = aiohttp.ClientSession()

        async with coordinator._session.post(
            f"{coordinator.url}/heartbeat",
            json={
                "device_id": target,
                "call_state": "do_not_disturb" if enabled else "idle",
            },
            timeout=aiohttp.ClientTimeout(total=5),
        ) as resp:
            pass

        await coordinator.async_request_refresh()

    # Register all services
    hass.services.async_register(
        DOMAIN, SERVICE_CALL,
        handle_call,
        schema=vol.Schema({
            vol.Required("target"): cv.string,
            vol.Optional("source", default="homeassistant"): cv.string,
        }),
    )

    hass.services.async_register(
        DOMAIN, SERVICE_BROADCAST,
        handle_broadcast,
        schema=vol.Schema({
            vol.Required("message"): cv.string,
            vol.Optional("targets", default="all"): cv.string,
        }),
    )

    hass.services.async_register(
        DOMAIN, SERVICE_HANGUP,
        handle_hangup,
        schema=vol.Schema({
            vol.Optional("call_id"): cv.string,
            vol.Optional("source", default="homeassistant"): cv.string,
            vol.Optional("target"): cv.string,
        }),
    )

    hass.services.async_register(
        DOMAIN, SERVICE_SET_DND,
        handle_set_dnd,
        schema=vol.Schema({
            vol.Required("target"): cv.string,
            vol.Optional("enabled", default=True): cv.boolean,
        }),
    )

    async def handle_configure_device(call: ServiceCall) -> None:
        """Handle hermes_intercom.configure_device service."""
        target = call.data["target"]
        settings = {}
        if "display_name" in call.data:
            settings["display_name"] = call.data["display_name"]
        if "room_location" in call.data:
            settings["room_location"] = call.data["room_location"]

        if not settings:
            _LOGGER.warning("configure_device: no settings provided")
            return

        if coordinator._session is None or coordinator._session.closed:
            coordinator._session = aiohttp.ClientSession()

        async with coordinator._session.post(
            f"{coordinator.url}/configure",
            json={"device_id": target, "settings": settings},
            timeout=aiohttp.ClientTimeout(total=10),
        ) as resp:
            result = await resp.json()
            if result.get("ok"):
                _LOGGER.info("Config pushed to %s: %s", target, settings)
            else:
                _LOGGER.warning("Config push failed for %s: %s", target, result)

    hass.services.async_register(
        DOMAIN, SERVICE_CONFIGURE_DEVICE,
        handle_configure_device,
        schema=vol.Schema({
            vol.Required("target"): cv.string,
            vol.Optional("display_name"): cv.string,
            vol.Optional("room_location"): cv.string,
        }),
    )
