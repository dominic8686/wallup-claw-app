"""Constants for the Hermes Intercom integration."""

DOMAIN = "hermes_intercom"
CONF_TOKEN_SERVER_URL = "token_server_url"
CONF_SCAN_INTERVAL = "scan_interval"

DEFAULT_TOKEN_SERVER_URL = "http://192.168.211.153:8090"
DEFAULT_SCAN_INTERVAL = 10  # seconds

# Signal types
SIGNAL_CALL_REQUEST = "call_request"
SIGNAL_CALL_ACCEPT = "call_accept"
SIGNAL_CALL_DECLINE = "call_decline"
SIGNAL_CALL_HANGUP = "call_hangup"
SIGNAL_ANNOUNCEMENT = "announcement"

# Events
EVENT_CALL_STARTED = f"{DOMAIN}_call_started"
EVENT_CALL_ENDED = f"{DOMAIN}_call_ended"
EVENT_CALL_MISSED = f"{DOMAIN}_call_missed"
EVENT_DEVICE_ONLINE = f"{DOMAIN}_device_online"
EVENT_DEVICE_OFFLINE = f"{DOMAIN}_device_offline"

# Services
SERVICE_CALL = "call"
SERVICE_BROADCAST = "broadcast"
SERVICE_HANGUP = "hangup"
SERVICE_ANSWER = "answer"
SERVICE_SET_DND = "set_dnd"
SERVICE_CONFIGURE_DEVICE = "configure_device"
SERVICE_START_CONVERSATION = "start_conversation"

# Platforms
PLATFORMS = ["binary_sensor", "sensor", "switch", "text", "select", "number", "button"]
