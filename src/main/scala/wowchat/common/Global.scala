package wowchat.common

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import io.netty.channel.EventLoopGroup
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import wowchat.discord.Discord
import wowchat.game.GameCommandHandler

import scala.collection.JavaConverters._
import scala.collection.mutable

object Global {

  var group: EventLoopGroup = _
  var config: WowChatConfig = _

  var discord: Discord = _

  // Sesiones de juego activas, keyed por session id. ConcurrentHashMap porque
  // distintas sesiones (distintos threads de netty) registran su handler en paralelo.
  private val gamesMap = new ConcurrentHashMap[String, GameCommandHandler]

  def setGame(sessionId: String, handler: GameCommandHandler): Unit = gamesMap.put(sessionId, handler)
  def removeGame(sessionId: String): Unit = gamesMap.remove(sessionId)
  def getGame(sessionId: String): Option[GameCommandHandler] = Option(gamesMap.get(sessionId))
  def activeSessionIds: Seq[String] =
    if (config == null) Seq.empty else config.sessions.filter(session => getGame(session.id).isDefined).map(_.id)

  // La sesion "por defecto" para comandos (?who, ?gmotd): la primera sesion
  // del config que este conectada en este momento.
  def defaultGame: Option[GameCommandHandler] =
    config.sessions.flatMap(session => getGame(session.id)).headOption

  // (discord channel name/id) -> bindings (sessionId, wow channel config)
  val discordToWow = new mutable.HashMap[String, mutable.Set[(String, WowChannelConfig)]]
    with mutable.MultiMap[String, (String, WowChannelConfig)]

  // (sessionId, wow type, wow channel name) -> discord channels
  val wowToDiscord = new mutable.HashMap[(String, Byte, Option[String]), mutable.Set[(TextChannel, DiscordChannelConfig)]]
    with mutable.MultiMap[(String, Byte, Option[String]), (TextChannel, DiscordChannelConfig)]

  // (sessionId, guild event key) -> discord channels
  val guildEventsToDiscord = new mutable.HashMap[(String, String), mutable.Set[TextChannel]]
    with mutable.MultiMap[(String, String), TextChannel]

  def getTime: String = {
    LocalDateTime.now.format(DateTimeFormatter.ofPattern("HH:mm:ss"))
  }
}
