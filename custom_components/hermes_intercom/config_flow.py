"""Config flow for Hermes Intercom."""

import aiohttp
import voluptuous as vol

from homeassistant import config_entries
from homeassistant.const import CONF_URL

from .const import DOMAIN, CONF_TOKEN_SERVER_URL, DEFAULT_TOKEN_SERVER_URL


class HermesIntercomConfigFlow(config_entries.ConfigFlow, domain=DOMAIN):
    """Handle a config flow for Hermes Intercom."""

    VERSION = 1

    async def async_step_user(self, user_input=None):
        """Handle the initial step — enter token server URL."""
        errors = {}

        if user_input is not None:
            url = user_input[CONF_TOKEN_SERVER_URL].rstrip("/")

            # Test connection to token server
            try:
                async with aiohttp.ClientSession() as session:
                    async with session.get(
                        f"{url}/health", timeout=aiohttp.ClientTimeout(total=5)
                    ) as resp:
                        if resp.status == 200:
                            # Check for devices endpoint too
                            async with session.get(
                                f"{url}/devices",
                                timeout=aiohttp.ClientTimeout(total=5),
                            ) as dev_resp:
                                if dev_resp.status == 200:
                                    await self.async_set_unique_id(url)
                                    self._abort_if_unique_id_configured()

                                    return self.async_create_entry(
                                        title="Hermes Intercom",
                                        data={CONF_TOKEN_SERVER_URL: url},
                                    )
                                else:
                                    errors["base"] = "cannot_connect"
                        else:
                            errors["base"] = "cannot_connect"
            except Exception:
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
