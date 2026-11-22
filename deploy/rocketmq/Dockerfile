# syntax=docker/dockerfile:1
# ============================================================
# RocketMQ 5.3.1 自建镜像
# 为什么不用 apache/rocketmq 官方镜像：
#   官方镜像的大层在 Docker Hub CDN 上，国内镜像加速器只缓存了热门镜像，
#   拉这个镜像会卡在 "Pulling fs layer" 不动。改成用国内 Apache 镜像站
#   下发行包自己装，稳定且可控。
# ============================================================
FROM eclipse-temurin:11-jre

ARG RMQ_VERSION=5.3.1
ARG RMQ_MIRROR=https://mirrors.aliyun.com/apache/rocketmq

ENV TZ=Asia/Shanghai \
    ROCKETMQ_HOME=/opt/rocketmq

RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

# 换成阿里云 Ubuntu 源，否则 archive.ubuntu.com 会卡到超时
RUN if [ -f /etc/apt/sources.list.d/ubuntu.sources ]; then \
        sed -i 's@//archive.ubuntu.com@//mirrors.aliyun.com@g; s@//security.ubuntu.com@//mirrors.aliyun.com@g' /etc/apt/sources.list.d/ubuntu.sources; \
    fi; \
    if [ -f /etc/apt/sources.list ]; then \
        sed -i 's@//archive.ubuntu.com@//mirrors.aliyun.com@g; s@//security.ubuntu.com@//mirrors.aliyun.com@g' /etc/apt/sources.list; \
    fi

RUN apt-get update \
 && apt-get install -y --no-install-recommends curl unzip ca-certificates \
 && curl -fL --retry 3 -o /tmp/rmq.zip "${RMQ_MIRROR}/${RMQ_VERSION}/rocketmq-all-${RMQ_VERSION}-bin-release.zip" \
 && mkdir -p /opt \
 && unzip -q /tmp/rmq.zip -d /opt \
 && mv "/opt/rocketmq-all-${RMQ_VERSION}-bin-release" "$ROCKETMQ_HOME" \
 && rm -f /tmp/rmq.zip \
 && rm -rf /var/lib/apt/lists/*

WORKDIR $ROCKETMQ_HOME
ENV PATH=$PATH:$ROCKETMQ_HOME/bin

# 官方启动脚本默认 -Xms4g~8g，宿主机内存不够会直接被 OOM Killer 杀掉，
# 这里统一把堆参数压到小尺寸（用正则，兼容不同小版本的写法差异）。
# 另外脚本里的 find_java_home 靠 `readlink -f $(which javac)` 推导，
# JRE 镜像没有 javac 会导致 JAVA_HOME 被清空而启动失败，
# 这里改成「已有 JAVA_HOME 就不覆盖」。
RUN for f in bin/runserver.sh bin/runbroker.sh bin/tools.sh; do \
        [ -f "$f" ] || continue; \
        sed -i 's/-Xms[0-9]\+[gG]/-Xms256m/g; s/-Xmx[0-9]\+[gG]/-Xmx512m/g; s/-Xmn[0-9]\+[gG]/-Xmn128m/g' "$f"; \
        sed -i 's/^find_java_home$/if [ -z "$JAVA_HOME" ]; then find_java_home; fi/' "$f"; \
    done \
 && mkdir -p /home/rocketmq/conf /home/rocketmq/store

EXPOSE 9876 10909 10911 10912
