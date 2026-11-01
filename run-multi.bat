chcp 65001
java -Xms64m -Xmx256m -XX:+UseSerialGC -XX:MaxMetaspaceSize=128m -Dfile.encoding=UTF-8 -Dlogback.configurationFile=logback.xml -jar matechat.jar matechat.conf
pause
