FROM openjdk:17-jdk-slim

RUN apt update && \
    apt install -y --no-install-recommends software-properties-common \
      ca-certificates gpg-agent curl gnupg
RUN curl -fsSL https://download.docker.com/linux/ubuntu/gpg | apt-key add - && \
  add-apt-repository "deb [arch=amd64] https://download.docker.com/linux/ubuntu bionic stable" && \
  apt update && \
  apt install -y --no-install-recommends docker-ce-cli=5:19.03.2~3-0~ubuntu-bionic

WORKDIR /
COPY *.jar jeed.jar
CMD [\
  "java",\
  "-ea", "--enable-preview",\
  "-Xmx1G","-Xss256k",\
  "-XX:+UseZGC","-XX:ZCollectionInterval=8",\
  "-Dfile.encoding=UTF-8",\
  "--add-exports", "jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",\
  "--add-exports", "jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED",\
  "--add-exports", "jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED",\
  "--add-exports", "jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED",\
  "--add-exports", "jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED",\
  "--add-exports", "java.management/sun.management=ALL-UNNAMED",\
  "-jar", "jeed.jar"\
]
# vim: tw=0
