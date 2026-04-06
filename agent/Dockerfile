FROM debian:13.4

# System deps
RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential python3 python3-pip \
    gcc python3-dev libffi-dev ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && ln -s /usr/bin/python3 /usr/bin/python

# Install voice-agent deps
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt --break-system-packages

COPY . .

CMD ["python", "main.py"]
