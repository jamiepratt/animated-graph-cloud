FROM clojure:temurin-21-tools-deps@sha256:db77923e67984d00cbf55a4e44cfacdefed5a8fcf1499469086ba1b569f9d937 AS build

WORKDIR /workspace
COPY deps.edn build.clj ./
RUN clojure -P -T:build
COPY src ./src
RUN clojure -T:build uber

FROM eclipse-temurin:21-jre-jammy@sha256:d63bd8d9b171999cbed8576f2c76e874dd4856791a358536e5c4d407e77edc13 AS ffmpeg-build

ARG FFMPEG_VERSION=8.1.2
ARG FFMPEG_SHA256=464beb5e7bf0c311e68b45ae2f04e9cc2af88851abb4082231742a74d97b524c

RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install --yes --no-install-recommends \
       build-essential \
       ca-certificates \
       curl \
       nasm \
       pkg-config \
       libx264-dev=2:0.163.3060+git5db6aa6-2build1 \
       xz-utils \
    && rm -rf /var/lib/apt/lists/*

RUN curl --fail --location --silent --show-error \
      "https://ffmpeg.org/releases/ffmpeg-${FFMPEG_VERSION}.tar.xz" \
      --output /tmp/ffmpeg.tar.xz \
    && echo "${FFMPEG_SHA256}  /tmp/ffmpeg.tar.xz" | sha256sum --check --strict \
    && mkdir /tmp/ffmpeg \
    && tar --extract --xz --file /tmp/ffmpeg.tar.xz --directory /tmp/ffmpeg --strip-components 1 \
    && cd /tmp/ffmpeg \
    && ./configure \
       --prefix=/opt/ffmpeg \
       --disable-autodetect \
       --disable-debug \
       --disable-doc \
       --disable-everything \
       --disable-network \
       --disable-shared \
       --enable-static \
       --enable-gpl \
       --enable-libx264 \
       --pkg-config-flags=--static \
       --enable-ffmpeg \
       --enable-ffprobe \
       --enable-avcodec \
       --enable-avformat \
       --enable-swresample \
       --enable-swscale \
       --enable-protocol=file,pipe \
       --enable-demuxers \
       --enable-muxer=mov,mp4 \
       --enable-decoders \
       --enable-parsers \
       --enable-bsfs \
       --enable-encoder=aac,libx264,prores_ks \
       --enable-filter=aformat,alimiter,amix,aresample,crop,fps,format,overlay,pad,scale,setsar \
    && make --jobs="$(nproc)" \
    && make install \
    && mkdir --parents /opt/ffmpeg/lib \
    && cp "$(pkg-config --variable=libdir x264)/libx264.so.163" \
          /opt/ffmpeg/lib/libx264.so.163 \
    && /opt/ffmpeg/bin/ffmpeg -version \
    && /opt/ffmpeg/bin/ffprobe -version

FROM eclipse-temurin:21-jre-jammy@sha256:d63bd8d9b171999cbed8576f2c76e874dd4856791a358536e5c4d407e77edc13

RUN groupadd --system app \
    && useradd --system --gid app --home-dir /app app
WORKDIR /app
COPY --from=ffmpeg-build /opt/ffmpeg/bin/ffmpeg /usr/local/bin/ffmpeg
COPY --from=ffmpeg-build /opt/ffmpeg/bin/ffprobe /usr/local/bin/ffprobe
COPY --from=ffmpeg-build /opt/ffmpeg/lib/libx264.so.163 /usr/local/lib/libx264.so.163
COPY --from=build --chown=app:app /workspace/target/animated-graph-cloud.jar ./animated-graph-cloud.jar

RUN ldconfig

USER app
ENV JAVA_TOOL_OPTIONS="-Djava.awt.headless=true -Xmx2g"
EXPOSE 8080
ENTRYPOINT ["java", "-cp", "/app/animated-graph-cloud.jar"]
CMD ["clojure.main", "-m", "agg.api.main"]
