package wowchat

import java.util.concurrent.{Executors, TimeUnit}

import wowchat.common.{CommonConnectionCallback, Global, ReconnectDelay, WowChatConfig, WowSession}
import wowchat.discord.Discord
import wowchat.game.GameConnector
import wowchat.realm.{RealmConnectionCallback, RealmConnector}
import com.typesafe.scalalogging.StrictLogging
import io.netty.channel.nio.NioEventLoopGroup

import scala.io.Source

object WoWChat extends StrictLogging {

  private val RELEASE = "v1.5.0-multisesion"

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
    logger.info(s"Sesiones configuradas: ${Global.config.sessions.map(_.id).mkString(", ")}")

    try {
      checkForNewVersion
    } catch {
      case e: Exception => logger.error("No se pudo revisar la version!", e)
    }

    // Un unico grupo de eventos compartido por todas las sesiones. Se crea UNA vez:
    // la reconexion de una sesion ya no mata a las demas.
    Global.group = new NioEventLoopGroup

    logger.info("C O N E C T A N D O...")
    Global.discord = new Discord(new CommonConnectionCallback {
      override def connected: Unit = Global.config.sessions.foreach(session => new SessionController(session).connect)
      override def error: Unit = sys.exit(1)
    })
  }

  // Controla el ciclo de vida (conectar/reconectar) de UNA sesion de WoW.
  class SessionController(session: WowSession) extends StrictLogging {

    private val reconnectExecutor = Executors.newSingleThreadScheduledExecutor
    private val reconnectDelay = new ReconnectDelay
    @volatile private var stopped = false

    private val gameEventCallback: CommonConnectionCallback = new CommonConnectionCallback {
      override def connected: Unit = reconnectDelay.reset

      override def disconnected: Unit = doReconnect

      override def error: Unit = {
        // Un error fatal de esta sesion (ej. personaje no encontrado) NO debe tumbar
        // a las demas. Se marca como detenida y no se reintenta.
        stopped = true
        logger.error(s"[${session.id}] Sesion detenida por error fatal. Las demas sesiones siguen activas.")
        Global.discord.updateSessionStatus(session.id, s"${session.id}: ERROR")
      }
    }

    def connect: Unit = {
      if (stopped) {
        return
      }

      val realmConnector = new RealmConnector(session, new RealmConnectionCallback {
        override def success(host: String, port: Int, realmName: String, realmId: Int, sessionKey: Array[Byte]): Unit = {
          new GameConnector(session, host, port, realmId, realmName, sessionKey, gameEventCallback).connect
        }

        override def disconnected: Unit = doReconnect

        override def error: Unit = {
          stopped = true
          logger.error(s"[${session.id}] Error de autenticacion (cuenta/contrasena). Sesion detenida; las demas siguen activas.")
          Global.discord.updateSessionStatus(session.id, s"${session.id}: AUTH ERROR")
        }
      })

      realmConnector.connect
    }

    def doReconnect: Unit = {
      if (stopped) {
        return
      }

      Global.discord.updateSessionStatus(session.id, "Conectando...")
      val delay = reconnectDelay.getNext
      logger.info(s"[${session.id}] desconectado, reconectando en $delay segundos...")

      reconnectExecutor.schedule(new Runnable {
        override def run(): Unit = connect
      }, delay, TimeUnit.SECONDS)
    }
  }

  private def printBanner(): Unit = {
    val banner = """
 /$$      /$$             /$$                /$$$$$$  /$$                   /$$
 | $$$    /$$$            | $$               /$$__  $$| $$                  | $$
 | $$$$  /$$$$  /$$$$$$  /$$$$$$    /$$$$$$ | $$  \__/| $$$$$$$   /$$$$$$  /$$$$$$
 | $$ $$/$$ $$ |____  $$|_  $$_/   /$$__  $$| $$      | $$__  $$ |____  $$|_  $$_/
 | $$  $$$| $$  /$$$$$$$  | $$    | $$$$$$$$| $$      | $$  \ $$  /$$$$$$$  | $$
 | $$\  $ | $$ /$$__  $$  | $$ /$$| $$_____/| $$    $$| $$  | $$ /$$__  $$  | $$ /$$
 | $$ \/  | $$|  $$$$$$$  |  $$$$/ |  $$$$$$$|  $$$$$$/| $$  | $$|  $$$$$$$  |  $$$/
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
