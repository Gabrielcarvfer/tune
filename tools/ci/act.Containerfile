# Image for replaying .github/workflows/android.yml locally with nektos/act:
# act's Ubuntu image plus the libraries the Android emulator loads, which
# GitHub's runners already have. See "Running the CI locally" in the README.
FROM docker.io/catthehacker/ubuntu:act-24.04
RUN apt-get update && apt-get install -y --no-install-recommends \
    libx11-xcb1 libxcb1 libxcomposite1 libxcursor1 libxdamage1 libxi6 libxtst6 libxrandr2 libxkbfile1 \
    libnss3 libpulse0 libasound2t64 libgl1 libegl1 libgbm1 libdrm2 libxkbcommon0 libxshmfence1 \
    && rm -rf /var/lib/apt/lists/*
