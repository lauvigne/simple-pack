FROM debian:bookworm-slim

ENV DEBIAN_FRONTEND=noninteractive
ENV LANG=C.UTF-8
ENV LC_ALL=C.UTF-8
ENV DISPLAY=:99

RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates \
    fonts-dejavu-core \
    libasound2 \
    libatk-bridge2.0-0 \
    libatk1.0-0 \
    libc6 \
    libcairo2 \
    libcups2 \
    libdbus-1-3 \
    libdrm2 \
    libgbm1 \
    libglib2.0-0 \
    libgtk-3-0 \
    libnss3 \
    libpango-1.0-0 \
    libx11-6 \
    libx11-xcb1 \
    libxcomposite1 \
    libxdamage1 \
    libxext6 \
    libxfixes3 \
    libxi6 \
    libxrandr2 \
    libxrender1 \
    libxss1 \
    libxtst6 \
    procps \
    tar \
    xvfb \
    xauth \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /opt/archi

# Copy a Linux packaging result such as build/impact-linux/Archi into the image.
COPY build/impact-linux/Archi/ /opt/archi/

# Default command prints the Archi CLI help.
# Override CMD to load a model and run a jArchi script.
CMD ["bash", "-lc", "xvfb-run -a /opt/archi/Archi -application com.archimatetool.commandline.app -consoleLog -nosplash --help"]
