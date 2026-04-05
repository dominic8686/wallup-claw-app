"""Config flow for Hermes Intercom."""

import aiohttp
import voluptuous as vol

from homeassistant import config_entries
from homeassistant.const import CONF_URL
from homeassistant.helpers.service_info.zeroconf import ZeroconfServiceInfo

from .const import DOMAIN, CONF_TOKEN_SERVER_URL, DEFAULT_TOKEN_SERVER_URL


async def _test_connection(url: str) -> bool:
    """Test that the token server is reachable and has a /devices endpoint."""
    try:
        async with aiohttp.ClientSession() as session:
            async with session.get(
                f"{url}/health", timeout=aiohttp.ClientTimeout(total=5)
            ) as resp:
                if resp.status != 200:
                    return False
            async with session.get(
                f"{url}/devices", timeout=aiohttp.ClientTimeout(total=5)
            ) as resp:
                return resp.status == 200
    except Exception:
        return False


class HermesIntercomConfigFlow(config_entries.ConfigFlow, domain=DOMAIN):
    """Handle a config flow for Hermes Intercom."""

    VERSION = 1

    _discovered_url: str | None = None
    _discovered_name: str | None = None

    async def async_step_user(self, user_input=None):
        """Handle the initial step — enter token server URL."""
        errors = {}

        if user_input is not None:
            url = user_input[CONF_TOKEN_SERVER_URL].rstrip("/")

            if await _test_connection(url):
                await self.async_set_unique_id(url)
                self._abort_if_unique_id_configured()
                return self.async_create_entry(
                    title="Hermes Intercom",
                    data={CONF_TOKEN_SERVER_URL: url},
                )
            else:
                errors["base"] = "cannot_connect"

        return self.async_show_form(
            step_id="user",
            data_schema=vol.Schema(
                {
                    vol.Required(
                        CONF_TOKEN_SERVER_URL,
                        default=DEFAULT_TOKEN_SERVER_URL,
                    ): str,
                }
            ),
            errors=errors,
        )

    async def async_step_zeroconf(self, discovery_info: ZeroconfServiceInfo):
        """Handle Zeroconf discovery of the token server."""
        host = discovery_info.host
        port = discovery_info.port or 8090
        self._discovered_url = f"http://{host}:{port}"
        self._discovered_name = discovery_info.name.split(".")[0]

        await self.async_set_unique_id(self._discovered_url)
        self._abort_if_unique_id_configured()

        self.context["title_placeholders"] = {"name": self._discovered_name}
        return await self.async_step_confirm_discovery()

    async def async_step_confirm_discovery(self, user_input=None):
        """Ask the user to confirm the discovered token server."""
        errors = {}

        if user_input is not None:
            if await _test_connection(self._discovered_url):
                return self.async_create_entry(
                    title=self._discovered_name or "Hermes Intercom",
                    data={CONF_TOKEN_SERVER_URL: self._discovered_url},
                )
            else:
                errors["base"] = "cannot_connect"

        self._set_confirm_only()
        return self.async_show_form(
            step_id="confirm_discovery",
            description_placeholders={"url": self._discovered_url},
            errors=errors,
        )
