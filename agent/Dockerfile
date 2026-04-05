FROM debian:13.4

# AGENT_MODE controls whether Hermes is installed at build time.
# openrouter (default) — lightweight, no Hermes
# hermes              — installs NousResearch/hermes-agent from GitHub
ARG AGENT_MODE=openrouter

# System deps — ffmpeg for audio, git for pip GitHub installs
RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential python3 python3-pip \
    ffmpeg gcc python3-dev libffi-dev git \
    && rm -rf /var/lib/apt/lists/* \
    && ln -s /usr/bin/python3 /usr/bin/python

# Install Hermes from GitHub only when building the hermes variant
RUN if [ "$AGENT_MODE" = "hermes" ]; then \
    pip install --no-cache-dir \
        "hermes-agent[all] @ git+https://github.com/NousResearch/hermes-agent.git" \
        --break-system-packages; \
    fi

# Install voice-agent specific deps
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt --break-system-packages

COPY . .

CMD ["python", "main.py"]
