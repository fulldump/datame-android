# Datame para Android

Envía solas a tu casa las fotos, los vídeos y los documentos del móvil. La app vive en segundo plano: cuando aparece una foto nueva, o cada hora, busca el NAS en la red de casa y le envía lo que aún no tiene. Nunca borra nada de la casa: es una copia, no un espejo.

## Emparejar

1. En la web del NAS, ve a «Conexiones» y pulsa «Añadir un móvil».
2. En la app, pulsa «Escanear el código del NAS» y apunta al código. También se puede copiar y pegar.

El código lleva las direcciones del NAS, la huella de su clave y un token para este móvil. La app solo habla con el equipo cuya clave tenga esa huella, aunque cambie de dirección, y nunca sabe tu contraseña. El token solo sirve para enviar y leer ficheros: no puede borrar, compartir ni administrar. Se puede quitar desde la misma lista de la web, y deja de funcionar al momento.

## Qué envía y dónde

- **Fotos y vídeos** de las carpetas de la galería que elijas (por defecto, la de la cámara), con el lugar donde se hicieron si le das permiso.
- **Todo lo demás**: descargas, documentos, PDF, música… de todo el móvil, con sus carpetas (`Download/factura.pdf`, `Music/…`). Android pide permitir el acceso a todos los archivos. Se dejan fuera `Android/` (el espacio privado de cada app) y los ficheros ocultos.
- **Otras carpetas** elegidas con el selector de Android, por ejemplo de una tarjeta SD.

«Pausar» corta el envío al momento, también el fichero que se está enviando: el NAS no guarda nada de un fichero a medias. Lo que se desmarca deja de enviarse antes del siguiente fichero.

Van al volumen elegido, dentro de la carpeta del móvil (`/Móvil/<nombre>` por defecto): las fotos por carpeta de la galería y año (`Camera/2026/IMG_1234.jpg`), los documentos con sus carpetas. Si un fichero cambia en el móvil, se envía otra vez sobre el mismo: la casa guarda la versión anterior en su historial.

Por defecto solo envía con Wi-Fi y con la batería no baja. Se puede pedir que espere también a estar cargando.

## Compilar

Hace falta el JDK 21 completo, con su compilador, no solo el Java que ejecuta programas: en Ubuntu, `sudo apt install openjdk-21-jdk-headless`. Gradle lo elige solo aunque Java por defecto sea otro. La primera vez, prepara el SDK de Android y Gradle (pide aceptar las licencias del SDK):

```sh
tools/setup.sh
```

Después:

```sh
./gradlew test assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Cómo está hecha

Kotlin y Jetpack Compose. WorkManager despierta la app cuando hay fotos nuevas (con un disparador de MediaStore) y cada hora, y la mantiene en primer plano mientras envía muchos ficheros. OkHttp habla con el NAS fijando la huella de su clave. Un SQLite pequeño recuerda qué ficheros están ya en la casa.

- `Pairing.kt`: lee el código del NAS.
- `Settings.kt`: la configuración, y el token cifrado con una clave del Android Keystore.
- `NasClient.kt`: la conexión con el NAS y las subidas.
- `Sources.kt`: qué ficheros tiene el móvil.
- `SyncWorker.kt` y `SyncScheduler.kt`: el envío y cuándo ocurre.
- `ui/`: las dos pantallas, emparejar y estado con configuración.
