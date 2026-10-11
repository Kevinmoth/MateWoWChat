package wowchat

import java.util.concurrent.{Executors, TimeUnit}

import wowchat.common.{CommonConnectionCallback, Global, ReconnectDelay, WowChatConfig}
import wowchat.discord.Discord
import wowchat.game.GameConnector
import wowchat.realm.{RealmConnectionCallback, RealmConnector}
import com.typesafe.scalalogging.StrictLogging
import io.netty.channel.nio.NioEventLoopGroup

import scala.io.Source

object WoWChat extends StrictLogging {

  private val RELEASE = "v1.4.0"

  def main(args: Array[String]): Unit = {
    printBanner()
    logger.info(s"Corriendo MateChat - $RELEASE")
    val confFile = if (args.nonEmpty) {
      args(0)
    } else {
      logger.info("No se proporcionó un archivo de configuración. Intentando con wowchat.conf por defecto.")
      "wowchat.conf"
    }
    Global.config = WowChatConfig(confFile)

    try {
      checkForNewVersion
    } catch {
      case e: Exception => logger.error("No se pudo revisar la version!", e)
    }

    val gameConnectionController: CommonConnectionCallback = new CommonConnectionCallback {

      private val reconnectExecutor = Executors.newSingleThreadScheduledExecutor
      private val reconnectDelay = new ReconnectDelay

      override def connect: Unit = {
        Global.group = new NioEventLoopGroup

        val realmConnector = new RealmConnector(new RealmConnectionCallback {
          override def success(host: String, port: Int, realmName: String, realmId: Int, sessionKey: Array[Byte]): Unit = {
            gameConnect(host, port, realmName, realmId, sessionKey)
          }

          override def disconnected: Unit = doReconnect

          override def error: Unit = sys.exit(1)
        })

        realmConnector.connect
      }

      private def gameConnect(host: String, port: Int, realmName: String, realmId: Int, sessionKey: Array[Byte]): Unit = {
        new GameConnector(host, port, realmName, realmId, sessionKey, this).connect
      }

      override def connected: Unit = reconnectDelay.reset

      override def disconnected: Unit = doReconnect

      def doReconnect: Unit = {
        Global.group.shutdownGracefully()
        Global.discord.changeRealmStatus("Conectando...")
        val delay = reconnectDelay.getNext
        logger.info(s"desconectado, reconectando en $delay segundos...")

        reconnectExecutor.schedule(new Runnable {
          override def run(): Unit = connect
        }, delay, TimeUnit.SECONDS)
      }
    }

    logger.info("C O N E C T A N D O...")
    Global.discord = new Discord(new CommonConnectionCallback {
      override def connected: Unit = gameConnectionController.connect

      override def error: Unit = sys.exit(1)
    })
  }

  private def printBanner(): Unit = {
    val banner = """
 /$$      /$$             /$$                /$$$$$$  /$$                   /$$    
| $$$    /$$$            | $$               /$$__  $$| $$                  | $$    
| $$$$  /$$$$  /$$$$$$  /$$$$$$    /$$$$$$ | $$  \__/| $$$$$$$   /$$$$$$  /$$$$$$  
| $$ $$/$$ $$ |____  $$|_  $$_/   /$$__  $$| $$      | $$__  $$ |____  $$|_  $$_/  
| $$  $$$| $$  /$$$$$$$  | $$    | $$$$$$$$| $$      | $$  \ $$  /$$$$$$$  | $$    
| $$\  $ | $$ /$$__  $$  | $$ /$$| $$_____/| $$    $$| $$  | $$ /$$__  $$  | $$ /$$
| $$ \/  | $$|  $$$$$$$  |  $$$$/|  $$$$$$$|  $$$$$$/| $$  | $$|  $$$$$$$  |  $$$$/
|__/     |__/ \_______/   \___/   \_______/ \______/ |__/  |__/ \_______/   \___/  
"""
    println(banner)
  }

  private def checkForNewVersion = {
    // This is JSON, but I really just didn't want to import a full blown JSON library for one string.
    val data = Source.fromURL("https://api.github.com/repos/fjaros/wowchat/releases/latest").mkString
    val regex = "\"tag_name\":\"(.+?)\",".r
    val repoTagName = regex
      .findFirstMatchIn(data)
      .map(_.group(1))
      .getOrElse("NOT FOUND")

    if (repoTagName != RELEASE) {
      logger.error( "~~~ !!!                Tu version esta desactualizada               !!! ~~~")
      logger.error(s"~~~ !!!                      Version:  $RELEASE                      !!! ~~~")
      logger.error(s"~~~ !!!                     vercion del repo    Version:  $repoTagName                      !!! ~~~")
      logger.error( "~~~ !!! descarga la actualizacion de https://github.com/kevinmoth/wowchat  !!! ~~~")
      logger.error( "~~~ !!!                Tu version esta desactualizada               !!! ~~~")
    }
  }
}