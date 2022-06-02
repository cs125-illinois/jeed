FROM openjdk:17-jdk-slim
WORKDIR /
COPY *.jar containerrunner.jar
ENV CLASSPATH=/containerrunner.jar:/jeed/
ENTRYPOINT [\
  "java",\
  "-ea", "--enable-preview",\
  "-Xss256k",\
  "-XX:+UseZGC","-XX:ZCollectionInterval=8",\
  "-Dfile.encoding=UTF-8",\
  "--add-exports", "jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",\
  "--add-exports", "jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED",\
  "--add-exports", "jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED",\
  "--add-exports", "jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED",\
  "--add-exports", "jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED",\
  "--add-exports", "java.management/sun.management=ALL-UNNAMED",\
  "edu.illinois.cs.cs125.jeed.containerrunner.MainKt"\
]
# vim: tw=0
