package wowchat.discord

import wowchat.commands.CommandHandler
import wowchat.common._
import com.typesafe.scalalogging.StrictLogging
import com.vdurmont.emoji.EmojiParser
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.JDA.Status
import net.dv8tion.jda.api.entities.{Activity, MessageType}
import net.dv8tion.jda.api.entities.Activity.ActivityType
import net.dv8tion.jda.api.entities.channel.ChannelType
import net.dv8tion.jda.api.events.StatusChangeEvent
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.events.session.ShutdownEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.requests.{CloseCode, GatewayIntent}
import net.dv8tion.jda.api.utils.MemberCachePolicy
import net.dv8tion.jda.api.utils.cache.CacheFlag
import wowchat.game.GamePackets

import scala.collection.JavaConverters._
import scala.collection.mutable

class Discord(discordConnectionCallback: CommonConnectionCallback) extends ListenerAdapter
  with GamePackets with StrictLogging {

  private val jda = JDABuilder
    .createDefault(Global.config.discord.token,
      GatewayIntent.GUILD_EXPRESSIONS,
      GatewayIntent.GUILD_MEMBERS,
      GatewayIntent.GUILD_MESSAGES,
      GatewayIntent.MESSAGE_CONTENT)
    .setMemberCachePolicy(MemberCachePolicy.ALL)
    .disableCache(CacheFlag.SCHEDULED_EVENTS,
      CacheFlag.VOICE_STATE,
      CacheFlag.ACTIVITY,
      CacheFlag.CLIENT_STATUS,
      CacheFlag.ONLINE_STATUS)
    .addEventListeners(this)
    .build

  private val messageResolver = MessageResolver(jda)

  // Estado de presencia por sesion: cada sesion de WoW reporta su texto y
  // el bot muestra la combinacion de todas en una sola presencia.
  private val sessionStatuses = mutable.LinkedHashMap[String, String]()
  private var firstConnect = true

  def updateSessionStatus(sessionId: String, message: String): Unit = {
    sessionStatuses.synchronized {
      sessionStatuses(sessionId) = message
      refreshSessionStatus
    }
  }

  private def refreshSessionStatus: Unit = {
    val combined = sessionStatuses.values.mkString(" | ")
    if (combined.nonEmpty && jda.getStatus == Status.CONNECTED) {
      jda.getPresence.setActivity(Activity.of(ActivityType.CUSTOM_STATUS, combined))
    }
  }

  private val recentMessages = mutable.Map[String, mutable.Queue[(String, Long)]]()
  private val duplicateWindow = 15000L // 15 segundos
  private val maxRecentMessages = 10

  private def limpiarCodigosWoW(mensaje: String): String = {
  mensaje.replaceAll("\\|[CcRr][a-fA-F0-9]{0,8}", "")  // Solo limpiar códigos de color
}
  private def aplicarFormatos(mensaje: String): String = {
    Global.config.discord.formatRules.foldLeft(mensaje) {
      case (result, rule) =>
        rule.compiled.matcher(result).replaceAll(rule.replacement)
    }
  }
//funcion para eliminar mensajes duplicados
private def levenshteinDistance(s1: String, s2: String): Int = {
  val memo = mutable.Map[(Int, Int), Int]()
  def min3(a: Int, b: Int, c: Int) = math.min(math.min(a, b), c)
  def distance(i: Int, j: Int): Int = memo.getOrElseUpdate((i, j), {
    if (i == 0) j
    else if (j == 0) i
    else min3(
      distance(i-1, j) + 1,
      distance(i, j-1) + 1,
      distance(i-1, j-1) + (if (s1(i-1) != s2(j-1)) 1 else 0)
    )
  })
  distance(s1.length, s2.length)
}

  // Neutraliza menciones masivas para que nadie pueda pinguear al servidor desde
  // el juego: se eliminan @everyone, @here y cualquier mencion raw <@ID> inyectada
  // como texto plano (que Discord renderizaria como mencion real).
  private def sanitizarMenciones(mensaje: String): String = {
    mensaje
      .replaceAll("(?i)@(everyone|here)\\b", "")
      .replaceAll("(?i)<@([&!]?\\d{1,32})>", "")
  }

  def sendMessageFromWow(sessionId: String, from: Option[String], message: String, wowType: Byte, wowChannel: Option[String]): Unit = {
    Global.wowToDiscord.get((sessionId, wowType, wowChannel.map(_.toLowerCase))).foreach(discordChannels => {
      val mensajeSeguro = sanitizarMenciones(message)
      val parsedLinks = messageResolver.resolveEmojis(messageResolver.stripColorCoding(limpiarCodigosWoW(messageResolver.resolveLinks(mensajeSeguro))))
      discordChannels.foreach {
        case (channel, channelConfig) =>
          val errors = mutable.ArrayBuffer.empty[String]
val parsedResolvedTags = from.map(_ => {
  messageResolver.resolveTags(channel, parsedLinks, errors += _)
})
  .getOrElse(parsedLinks)
  .replace("`", "\\`")
  .replace("*", "\\*")
  .replace("_", "\\_")
  .replace("~", "\\~")

// Aplicar reglas de formato DESPUÉS del escapado (format_rules del config)
val finalMessageContent = aplicarFormatos(parsedResolvedTags)
//--auxiliares para eliminar duplicados

val channelName = s"${channel.getId}-${wowChannel.getOrElse("default")}"
val messageContent = finalMessageContent.trim
val now = System.currentTimeMillis()

val channelQueue = recentMessages.getOrElseUpdate(channelName, mutable.Queue[(String, Long)]())
channelQueue.dequeueAll(_._2 < now - duplicateWindow)

val isDuplicate = channelQueue.exists { case (msg, _) => 
  msg.equals(messageContent) || levenshteinDistance(msg, messageContent) < 3
}

if (isDuplicate) {
  logger.debug(s"Mensaje duplicado descartado en $channelName")
  return
}

channelQueue.enqueue((messageContent, now))
if (channelQueue.length > maxRecentMessages) {
  channelQueue.dequeue()
}
//--auxiliares para eliminar duplicados FIN


val formatted = channelConfig
  .format
  .replace("%time", Global.getTime)
  .replace("%user", from.getOrElse(""))
  .replace("%message", finalMessageContent)  // Cambiar aquí
  .replace("%target", wowChannel.getOrElse(""))

          val filter = shouldFilter(channelConfig.filters, formatted)
          logger.info(s"${if (filter) "FILTERED " else ""}WoW->Discord(${channel.getName}) $formatted")
          if (!filter) {
            channel.sendMessage(formatted).queue()
          }
          if (Global.config.discord.enableTagFailedNotifications) {
            errors.foreach(error => {
              Global.getGame(sessionId).foreach(_.sendMessageToWow(ChatEvents.CHAT_MSG_WHISPER, error, from))
              channel.sendMessage(error).queue()
            })
          }
      }
    })
  }

  def sendGuildNotification(sessionId: String, eventKey: String, message: String): Unit = {
    val mensajeSeguro = sanitizarMenciones(message)
    Global.guildEventsToDiscord
      .getOrElse(
        (sessionId, eventKey),
        Global.wowToDiscord.getOrElse(
          (sessionId, ChatEvents.CHAT_MSG_GUILD, None), mutable.Set.empty
        ).map(_._1)
      )
      .foreach(channel => {
        logger.info(s"WoW->Discord(${channel.getName}) $mensajeSeguro")
        channel.sendMessage(mensajeSeguro).queue()
      })
  }

  def sendAchievementNotification(sessionId: String, name: String, achievementId: Int): Unit = {
    Global.config.guildConfig.notificationConfigs.get("Logro").fold({
      logger.warn("No existe la configuracion de notificacion 'Logro' en guild; logro ignorado")
    })(notificationConfig => {
      if (notificationConfig.enabled) {
        Global.wowToDiscord.get((sessionId, ChatEvents.CHAT_MSG_GUILD, None))
          .foreach(_.foreach {
            case (discordChannel, _) =>
              val formatted = notificationConfig
                .format
                .replace("%time", Global.getTime)
                .replace("%user", name)
                .replace("%achievement", messageResolver.resolveAchievementId(achievementId))

              discordChannel.sendMessage(formatted).queue()
          })
      }
    })
  }

  override def onStatusChange(event: StatusChangeEvent): Unit = {
    event.getNewStatus match {
      case Status.CONNECTED =>
        sessionStatuses.synchronized(refreshSessionStatus)
        // this is a race condition if already connected to wow, reconnect to discord, and bot tries to send
        // wow->discord message. alternatively it was throwing already garbage collected exceptions if trying
        // to use the previous connection's channel references. I guess need to refill these maps on discord reconnection
        Global.discordToWow.clear
        Global.wowToDiscord.clear
        Global.guildEventsToDiscord.clear

        val sessionIds = Global.config.sessions.map(_.id)

        // getNext seq of needed channels from config
        val configChannels = Global.config.channels.map(channelConfig => {
          channelConfig.discord.channel.toLowerCase -> channelConfig
        })
        val configChannelsNames = configChannels.map(_._1)

        val discordTextChannels = event.getEntity.getTextChannels.asScala
        val eligibleDiscordChannels = discordTextChannels
          .filter(channel =>
            configChannelsNames.contains(channel.getName.toLowerCase) ||
            configChannelsNames.contains(channel.getId)
          )

        // build directional maps (con la sesion de WoW como parte de la clave)
        eligibleDiscordChannels.foreach(channel => {
          configChannels
            .filter {
              case (name, _) =>
                name.equalsIgnoreCase(channel.getName) ||
                name == channel.getId
            }
            .foreach {
              case (name, channelConfig) =>
                val sessionId = channelConfig.wow.session.getOrElse(Global.config.defaultSessionId)

                if (channelConfig.chatDirection == ChatDirection.both ||
                  channelConfig.chatDirection == ChatDirection.discord_to_wow) {
                  Global.discordToWow.addBinding(
                    name.toLowerCase, (sessionId, channelConfig.wow)
                  )
                }

                if (channelConfig.chatDirection == ChatDirection.both ||
                  channelConfig.chatDirection == ChatDirection.wow_to_discord) {
                  Global.wowToDiscord.addBinding(
                    (sessionId, channelConfig.wow.tp, channelConfig.wow.channel.map(_.toLowerCase)),
                    (channel, channelConfig.discord)
                  )
                }
            }
          })

        // build guild notification maps (sin sesion = enlazado a todas las sesiones)
        val guildEventChannels = Global.config.guildConfig.notificationConfigs
          .filter {
            case (_, notificationConfig) =>
              notificationConfig.enabled
          }
          .flatMap {
            case (key, notificationConfig) =>
              notificationConfig.channel.map(key -> _)
          }

        discordTextChannels.foreach(channel => {
          guildEventChannels
            .filter {
              case (_, name) =>
                name.equalsIgnoreCase(channel.getName) ||
                name == channel.getId
            }
            .foreach {
              case (notificationKey, _) =>
                val guildEventSessionIds = Global.config.guildConfig.notificationConfigs(notificationKey).session match {
                  case Some(session) => Seq(session)
                  case None => sessionIds
                }
                guildEventSessionIds.foreach(sessionId =>
                  Global.guildEventsToDiscord.addBinding((sessionId, notificationKey), channel)
                )
            }
        })

        // Canales configurados que el bot no puede ver (ID mal copiado, permisos
        // insuficientes o el bot no esta en ese servidor). Sin esto fallarian en silencio.
        val missingChannels = configChannels.filter {
          case (name, _) =>
            !discordTextChannels.exists(channel =>
              name.equalsIgnoreCase(channel.getName) || name == channel.getId
            )
        }.map(_._2.discord.channel)
        if (missingChannels.nonEmpty) {
          logger.warn(s"Canales configurados pero NO visibles para el bot: ${missingChannels.mkString(", ")}")
        }

        if (Global.discordToWow.nonEmpty || Global.wowToDiscord.nonEmpty) {
          if (firstConnect) {
            discordConnectionCallback.connected
            firstConnect = false
          } else {
            discordConnectionCallback.reconnected
          }
        } else {
          logger.error("No hay canales de Discord configurados!")
        }
      case Status.DISCONNECTED =>
        discordConnectionCallback.disconnected
      case _ =>
    }
  }

  override def onShutdown(event: ShutdownEvent): Unit = {
    event.getCloseCode match {
      case CloseCode.DISALLOWED_INTENTS =>
        logger.error("Por nuevas reglas de Discord, debes marcar las casillas SERVER MEMBERS INTENT y MESSAGE CONTENT INTENT en el portal de desarrolladores para este bot. Puedes encontrar más información en https://discord.com/developers/docs/topics/gateway#privileged-intents")
      case _ =>
    }
  }

  override def onMessageReceived(event: MessageReceivedEvent): Unit = {
    // ignore messages received from self
    if (event.getAuthor.getIdLong == jda.getSelfUser.getIdLong) {
      return
    }

    // ignore messages from non-text channels
    if (event.getChannelType != ChannelType.TEXT) {
      return
    }

    // ignore non-default messages
    val messageType = event.getMessage.getType
    if (messageType != MessageType.DEFAULT && messageType != MessageType.INLINE_REPLY) {
      return
    }

    val channel = event.getChannel
    val channelId = channel.getId
    val channelName = event.getChannel.getName.toLowerCase
    val effectiveName = sanitizeName(event.getMember.getEffectiveName)
    val message = sanitizarMenciones((sanitizeMessage(event.getMessage.getContentDisplay) +: event.getMessage.getAttachments.asScala.map(_.getUrl))
      .filter(_.nonEmpty)
      .mkString(" "))
    val enableCommandsChannels = Global.config.discord.enableCommandsChannels
    logger.debug(s"RECV DISCORD MESSAGE: [${channel.getName}] [$effectiveName]: $message")
    if (message.isEmpty) {
      logger.error(s"recibido en ${channel.getName} pero el contenido estaba vacío. Es posible que hayas olvidado habilitar MESSAGE CONTENT INTENT para tu bot en el portal de desarrolladores de Discord.")
    }

    if ((enableCommandsChannels.nonEmpty && !enableCommandsChannels.contains(channelName)) || !CommandHandler(channel, message)) {
      // send to all configured wow channels (cada binding lleva su sesion de WoW)
      Global.discordToWow
        .get(channelName)
        .fold(Global.discordToWow.get(channelId))(Some(_))
        .foreach(_.foreach {
          case (sessionId, channelConfig) =>
            Global.getGame(sessionId).fold({
              logger.error(s"La sesion '$sessionId' no esta conectada! Mensaje descartado.")
            })(handler => {
              val finalMessages = if (shouldSendDirectly(message)) {
                Seq(message)
              } else {
                splitUpMessage(channelConfig.format, effectiveName, message)
              }

              finalMessages.foreach(finalMessage => {
                val filter = shouldFilter(channelConfig.filters, finalMessage)
                logger.info(s"${if (filter) "FILTERED " else ""}Discord->WoW[$sessionId](${
                  channelConfig.channel.getOrElse(ChatEvents.valueOf(channelConfig.tp))
                }) $finalMessage")
                if (!filter) {
                  handler.sendMessageToWow(channelConfig.tp, finalMessage, channelConfig.channel)
                }
              })
            })
        })
    }
  }

  def shouldSendDirectly(message: String): Boolean = {
    val discordConf = Global.config.discord
    val trimmed = message.drop(1).toLowerCase

    message.startsWith(".") &&
    discordConf.enableDotCommands &&
      (
        discordConf.dotCommandsWhitelist.isEmpty ||
        discordConf.dotCommandsWhitelist.contains(trimmed) ||
        // Theoretically it would be better to construct a prefix tree for this.
        !discordConf.dotCommandsWhitelist.forall(item => {
          if (item.endsWith("*")) {
            !trimmed.startsWith(item.dropRight(1).toLowerCase)
          } else {
            true
          }
        })
      )
  }

  def shouldFilter(filtersConfig: Option[FiltersConfig], message: String): Boolean = {
    filtersConfig
      .fold(Global.config.filters)(Some(_))
      .exists(filters => filters.enabled && filters.patterns.exists(message.matches))
  }

  def sanitizeName(name: String): String = {
    name.replace("|", "||")
  }

  def sanitizeMessage(message: String): String = {
    EmojiParser.parseToAliases(message, EmojiParser.FitzpatrickAction.REMOVE)
  }

  def splitUpMessage(format: String, name: String, message: String): Seq[String] = {
    val retArr = mutable.ArrayBuffer.empty[String]
    val maxTmpLen = 255 - format
      .replace("%time", Global.getTime)
      .replace("%user", name)
      .replace("%message", "")
      .length

    var tmp = message
    while (tmp.length > maxTmpLen) {
      val subStr = tmp.substring(0, maxTmpLen)
      val spaceIndex = subStr.lastIndexOf(' ')
      tmp = if (spaceIndex == -1) {
        retArr += subStr
        tmp.substring(maxTmpLen)
      } else {
        retArr += subStr.substring(0, spaceIndex)
        tmp.substring(spaceIndex + 1)
      }
    }

    if (tmp.nonEmpty) {
      retArr += tmp
    }

    retArr
      .map(message => {
        val formatted = format
          .replace("%time", Global.getTime)
          .replace("%user", name)
          .replace("%message", message)

        // If the final formatted message is a dot command, it should be disabled. Add a space in front.
        if (formatted.startsWith(".")) {
          s" $formatted"
        } else {
          formatted
        }
      })
  }
}
