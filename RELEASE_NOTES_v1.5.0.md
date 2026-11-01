# Matechat 1.5.0 — Multi-sesión

**Matechat** es el fork mejorado de `wowchat` (producto base): retransmite canales de chat de NaerZone a Discord con **un solo bot** y **varios personajes de WoW** conectados a la vez.

## Novedades

- **Multi-sesión**: 1 bot de Discord, N personajes (horda / alianza / español) definidos en `wow.sessions`. Un solo event loop compartido: la reconexión de una sesión ya no tumba a las demás.
- **Formato y emotes configurables sin recompilar**: reglas en `discord.format_rules` (regex → reemplazo) para traducir y decorar los mensajes.
- **Sanitización de menciones masivas** en todo el flujo de retransmisión.
- **Aviso de canales no visibles** y formato uniforme para `[Taberna]`.
- **Tabla de ruteo visible al arrancar**: muestra qué canal de WoW va a qué canal de Discord y con qué sesión.
- **Filtros de lenguaje** (español) y resolución de links de items/spells/profesiones a `comunidadnaer.com`.
- **Plantilla y guía de despliegue** (`matechat.conf.example`) para hosting Linux con systemd.

## Requisitos

- Java 11, 17 o 21 (el jar es compatible con cualquiera; probado en Java 21).
- En el portal de Discord (tu app → Bot):
  - `SERVER MEMBERS INTENT` activado
  - `MESSAGE CONTENT INTENT` activado
- Invitar el bot con permisos: Ver canales, Enviar mensajes, Incrustar enlaces, Leer historial.

## Instalación

1. Descargar y descomprimir `Matechat_1.5.0.zip`.
2. Copiar `matechat.conf.example` como `matechat.conf` y completar:
   - `discord.token` → token del bot
   - `wow.sessions.*` → cuenta / clave / personaje de cada sesión
   - `chat.channels.*` → IDs de los canales de Discord
3. Arrancar:
   - Windows: `run.bat`
   - Linux/macOS: `./run.sh`
   - Manual: `java -jar matechat.jar matechat.conf`

> El zip **no incluye configuración real**: solo la plantilla. Las credenciales quedan en tu `matechat.conf` local.
> Si venís de una versión anterior, `wowchat.conf` se sigue leyendo como respaldo y el programa avisa que lo renombres a `matechat.conf`.

## Contenido del asset

`Matechat_1.5.0.zip`
- `matechat.jar` (shaded, 30 MB)
- `logback.xml`
- `matechat.conf.example`
- `run.bat` / `run.sh`

## Integridad

```
SHA256  Matechat_1.5.0.zip  8eec520cb1f59b7a73818345ab9d694561b3164cffafdbbb5fe7065fef3a4361
SHA256  matechat.jar        c633dcd1fe42be38c46155bc21f908a7041e3ebe09bba06344369a28809df079
```

## Cambios desde v1.4.1

- v1.5.0: soporte multi-sesión (1 bot, N personajes)
- Fixes del test multi-sesión: resolver de links y encoding
- Build de producción: retiro de diagnósticos + plantilla y guía de despliegue
- Emotes y formato configurables vía `discord.format_rules` (sin recompilar)
- Aviso de canales no visibles + formato uniforme `[Taberna]`
- Sanitizar menciones masivas en todo el flujo
- Tabla de ruteo visible al arrancar
- Limpieza del repositorio (artefactos de prueba, imágenes y docs auxiliares)
- Renombrado visible a **Matechat** (jar, zip, configs y scripts); el paquete Java interno sigue siendo `wowchat`
