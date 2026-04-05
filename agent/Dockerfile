FROM debian:13.4

# System deps — matches Hermes Dockerfile base + ffmpeg for audio processing
RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential python3 python3-pip \
    ffmpeg gcc python3-dev libffi-dev \
    && rm -rf /var/lib/apt/lists/* \
    && ln -s /usr/bin/python3 /usr/bin/python

# Install Hermes from the additional build context (injected by docker-compose).
# This bakes the Hermes code + all its Python deps into the image.
COPY --from=hermes . /opt/hermes
RUN pip install --no-cache-dir -e "/opt/hermes[all]" --break-system-packages

# Install voice-agent specific deps on top
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt --break-system-packages

COPY . .

CMD ["python", "agent.py"]
