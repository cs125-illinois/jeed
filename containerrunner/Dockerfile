FROM openjdk:16-jdk-slim
WORKDIR /
COPY *.jar containerrunner.jar
ENV CLASSPATH=/containerrunner.jar:/jeed/
ENTRYPOINT [ "java", "-ea", "--enable-preview", "-Xss256k", "-Dfile.encoding=UTF-8", "--illegal-access=permit", "edu.illinois.cs.cs125.jeed.containerrunner.MainKt" ]
