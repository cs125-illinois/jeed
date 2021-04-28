FROM openjdk:16-jdk-slim

RUN apt update && \
    apt install -y --no-install-recommends software-properties-common \
      ca-certificates gpg-agent curl gnupg
RUN curl -fsSL https://download.docker.com/linux/ubuntu/gpg | apt-key add - && \
  add-apt-repository "deb [arch=amd64] https://download.docker.com/linux/ubuntu bionic stable" && \
  apt update && \
  apt install -y --no-install-recommends docker-ce-cli=5:19.03.2~3-0~ubuntu-bionic

WORKDIR /
COPY *.jar jeed.jar
CMD [ "java", "-ea", "--enable-preview", "-Xss256k", "-jar", "jeed.jar" ]
