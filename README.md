# word2pdf: conversión de contratos DOCX a PDF con ONLYOFFICE Docs

Microservicio Java 21 / Spring Boot 4.1 que recibe un documento Word (`.docx`) como bytes, lo convierte con
**ONLYOFFICE Docs Community 10.0.0** mediante el endpoint `POST /converter/from-file` y devuelve el PDF como bytes.
No guarda documentos de forma permanente y no necesita publicar los contratos por URL.

> **Fidelidad.** ONLYOFFICE ofrece una conversión de alta calidad, pero **no garantiza una fidelidad del 100 %**
> respecto a Microsoft Word. Este servicio incluye controles automáticos (tamaño de página y fuentes) y una herramienta
> de QA que compara el resultado con PDFs exportados desde Word. Ver [Fidelidad documental](#fidelidad-documental).

---

## Índice

1. [Arquitectura](#arquitectura)
2. [Requisitos](#1-requisitos)
3. [Variables de entorno](#2-configurar-variables-de-entorno)
4. [Docker Compose](#3-ejecutar-docker-compose)
5. [Comprobar ONLYOFFICE](#4-comprobar-que-onlyoffice-está-operativo)
6. [Levantar el servicio Java](#5-levantar-el-servicio-java)
7. [Enviar un Word (cURL / Postman)](#6-enviar-un-archivo-word-curl--postman)
8. [Recibir y guardar el PDF](#7-recibir-y-guardar-el-pdf)
9. [Pruebas](#8-ejecutar-las-pruebas)
10. [Fuentes adicionales](#9-instalar-fuentes-adicionales)
11. [Errores de conversión](#10-identificar-errores-de-conversión)
12. [Fidelidad documental](#fidelidad-documental)
13. [Seguridad](#seguridad)
14. [Almacenamiento temporal en ONLYOFFICE](#almacenamiento-temporal-en-onlyoffice)
15. [Rendimiento y configuración](#rendimiento-y-configuración)
16. [Integración con la API de ONLYOFFICE: notas técnicas](#integración-con-la-api-de-onlyoffice-notas-técnicas)
17. [Estado de verificación](#estado-de-verificación)

---

## Arquitectura

```text
Cliente (app externa / Postman)
   │  POST /api/v1/conversion/docx-to-pdf   (application/octet-stream, X-API-Key)
   ▼
┌──────────────────────── word2pdf (Spring Boot, puerto 8080) ────────────────────────┐
│ RequestIdFilter → ApiKeyAuthFilter → ConversionController (solo adapta HTTP)         │
│        │                                                                             │
│        ▼                                                                             │
│ DocumentConversionService (DefaultDocumentConversionService)                         │
│   1. DocxValidator        firma ZIP/OOXML, anti zip-bomb, tipo real DOCX            │
│   2. DocxLayoutInspector  tamaños de página y fuentes declaradas (solo lectura)      │
│   3. ConversionLimiter    semáforo de conversiones simultáneas (503 si se satura)    │
│   4. ConversionEngine ──► OnlyOfficeClient (HttpClient reutilizado, JWT HS256)       │
│   5. PdfValidator         PDFBox: PDF válido, ≥1 página, tamaños, fuentes            │
│   6. FidelityChecker      tamaño de página y fuentes DOCX vs PDF → avisos o 422       │
│   7. Métricas Micrometer  docconv.conversion.duration{outcome}                       │
└──────────────────────────────────────┬───────────────────────────────────────────────┘
                                       │ multipart: file + token (red Docker interna "backend")
                                       ▼
                     ONLYOFFICE Docs 10.0.0  POST /converter/from-file
                     (sin puertos publicados, red sin salida a Internet)
```

Estructura del código (`src/main/java/pe/saeta/word2pdf/`):

| Paquete | Responsabilidad |
|---|---|
| `controller` | API REST (`ConversionController`) |
| `service` | Contrato `DocumentConversionService`, orquestación, límite de concurrencia, puerto `ConversionEngine` |
| `client` | Cliente ONLYOFFICE (`OnlyOfficeClient`), firma JWT, códigos de error, health indicator |
| `config` | Propiedades tipadas, `HttpClient` compartido, filtros de request-id y API key |
| `exception` | `ErrorCode`, `ConversionException`, `GlobalExceptionHandler` (RFC 9457) |
| `validation` | Validación DOCX y PDF, lectura acotada del cuerpo, XML seguro (anti-XXE) |
| `fidelity` | Inspección de maquetación DOCX y controles de fidelidad |
| `quality` | Herramienta de QA `PdfComparisonTool` (no se ejecuta en producción) |

Para cambiar de motor de conversión basta con otra implementación de `ConversionEngine`; la API y
`DocumentConversionService` no cambian.

---

## 1. Requisitos

| Herramienta | Versión | Uso |
|---|---|---|
| Docker Desktop (Windows/macOS) o Docker Engine + Compose v2 (Linux) | Compose ≥ 2.20 | Ejecutar ONLYOFFICE y el servicio |
| JDK 21 (p.ej. Eclipse Temurin) | 21 LTS | Compilar y ejecutar pruebas fuera de Docker |
| Maven | No hace falta: se incluye Maven Wrapper (`mvnw` / `mvnw.cmd`, Maven 3.9.16) | |
| cURL o Postman | — | Probar la API |

Recursos recomendados: **4 GB de RAM y 2 vCPU para ONLYOFFICE** y 1 GB para el servicio Java. En Windows, Docker
Desktop necesita WSL 2 activado.

## 2. Configurar variables de entorno

Genere `.env` con secretos aleatorios (nunca lo versione):

```powershell
# Windows
powershell -ExecutionPolicy Bypass -File scripts\generate-env.ps1
```

```bash
# Linux / macOS
./scripts/generate-env.sh
```

O copie `.env.example` a `.env` y complete los valores a mano:

| Variable | Descripción |
|---|---|
| `ONLYOFFICE_JWT_SECRET` | Secreto compartido HS256 entre el servicio y ONLYOFFICE (≥ 32 bytes). **Obligatorio** |
| `APP_API_KEYS` | API keys de los clientes, separadas por comas (≥ 24 caracteres cada una). **Obligatorio** |
| `APP_BIND` / `APP_PORT` | Interfaz y puerto publicados del servicio (por defecto `127.0.0.1:8080`) |
| `APP_MAX_CONCURRENT` | Conversiones simultáneas hacia ONLYOFFICE (por defecto 4) |
| `APP_MAX_INPUT_SIZE` | Tamaño máximo del DOCX (por defecto `20MB`) |
| `APP_QUEUE_TIMEOUT` | Espera máxima por un cupo antes de responder 503 (por defecto `30s`) |
| `ONLYOFFICE_CONVERSION_TIMEOUT` | Tiempo máximo de una conversión (por defecto `120s`) |
| `APP_FIDELITY_FAIL_ON_FONTS` | `true` = responder 422 si se sospecha sustitución de fuentes |
| `APP_FIDELITY_FAIL_ON_PAGE_SIZE` | `true` = responder 422 si el tamaño de página no coincide |
| `FONTS_DIR` | Directorio de fuentes adicionales (por defecto `./fonts`) |

El secreto JWT y las API keys solo se leen de variables de entorno; no hay valores por defecto en el código.
El servicio **no arranca** si falta el secreto o si es demasiado corto.

## 3. Ejecutar Docker Compose

```bash
docker compose up -d --build
docker compose ps
```

El primer arranque descarga la imagen de ONLYOFFICE (unos 3-4 GB) y puede tardar varios minutos. El servicio
`word2pdf` espera a que ONLYOFFICE esté *healthy* (`depends_on: service_healthy`).

Solo se publica el puerto del servicio Java (`127.0.0.1:8080`). ONLYOFFICE queda en la red interna `backend`
(`internal: true`): no es accesible desde el host ni tiene salida a Internet.

## 4. Comprobar que ONLYOFFICE está operativo

```bash
# Estado del contenedor (debe indicar "healthy")
docker compose ps onlyoffice

# Health check interno de ONLYOFFICE (responde "true")
docker compose exec onlyoffice curl -s http://localhost/healthcheck

# Readiness del servicio Java: incluye la verificación de ONLYOFFICE (HTTP 200 = listo, 503 = motor no disponible)
curl -i http://127.0.0.1:8080/actuator/health/readiness
```

## 5. Levantar el servicio Java

**Opción A, Docker (recomendada):** ya queda levantado con `docker compose up -d --build`.

**Opción B, desarrollo local con el JDK:** publique ONLYOFFICE solo en localhost con el override de desarrollo y
ejecute la aplicación con el wrapper:

```powershell
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d onlyoffice

# PowerShell: cargue las variables de .env en la sesión
Get-Content .env | Where-Object { $_ -match '^[A-Z_]+=.' } | ForEach-Object { $k,$v = $_ -split '=',2; Set-Item "env:$k" $v }
$env:ONLYOFFICE_URL = "http://localhost:8081"
.\mvnw.cmd spring-boot:run
```

```bash
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d onlyoffice
set -a; . ./.env; set +a
ONLYOFFICE_URL=http://localhost:8081 ./mvnw spring-boot:run
```

**Opción C, JAR:** `./mvnw -DskipTests package` y `java -jar target/word2pdf.jar` (con las mismas variables).

## 6. Enviar un archivo Word (cURL / Postman)

El repositorio incluye un contrato de ejemplo **ficticio**: [samples/contrato-ejemplo.docx](samples/contrato-ejemplo.docx).
Tiene varias páginas, tablas con celdas combinadas, encabezado con logotipo, numeración de páginas, sección horizontal
y bloque de firmas.

En los ejemplos, `APP_API_KEY` es una de las claves definidas en `APP_API_KEYS` (`.env`):

```bash
export APP_API_KEY=$(grep '^APP_API_KEYS=' .env | cut -d= -f2 | cut -d, -f1)
```

```powershell
$env:APP_API_KEY = ((Get-Content .env | Select-String '^APP_API_KEYS=').Line -replace '^APP_API_KEYS=','').Split(',')[0]
```

### Endpoint principal: cuerpo binario

```bash
curl -X POST "http://127.0.0.1:8080/api/v1/conversion/docx-to-pdf?filename=contrato-ejemplo.docx" \
  -H "X-API-Key: $APP_API_KEY" \
  -H "Content-Type: application/octet-stream" \
  --data-binary @samples/contrato-ejemplo.docx \
  -o contrato-ejemplo.pdf -D -
```

```powershell
# Windows (use curl.exe, no el alias de PowerShell)
curl.exe -X POST "http://127.0.0.1:8080/api/v1/conversion/docx-to-pdf?filename=contrato-ejemplo.docx" `
  -H "X-API-Key: $env:APP_API_KEY" -H "Content-Type: application/octet-stream" `
  --data-binary "@samples\contrato-ejemplo.docx" -o contrato-ejemplo.pdf -D -
```

### Endpoint multipart (Postman / formularios)

```bash
curl -X POST "http://127.0.0.1:8080/api/v1/conversion/docx-to-pdf/multipart" \
  -H "X-API-Key: $APP_API_KEY" \
  -F "file=@samples/contrato-ejemplo.docx" \
  -o contrato-ejemplo.pdf
```

**Postman:** método `POST`, URL `http://127.0.0.1:8080/api/v1/conversion/docx-to-pdf/multipart`, cabecera `X-API-Key`,
*Body → form-data*, clave `file` de tipo *File*, y luego *Send and Download*. Para el endpoint binario use
*Body → binary* y la cabecera `Content-Type: application/octet-stream`.

### Diagnóstico de maquetación (sin convertir)

```bash
curl -X POST "http://127.0.0.1:8080/api/v1/conversion/docx/inspect" \
  -H "X-API-Key: $APP_API_KEY" -H "Content-Type: application/octet-stream" \
  --data-binary @samples/contrato-ejemplo.docx
# {"sections":[{"pageWidthTwips":11906,"pageHeightTwips":16838,"landscape":false,...}],"fonts":["Arial","Times New Roman"]}
```

## 7. Recibir y guardar el PDF

Respuesta exitosa:

```
HTTP/1.1 200
Content-Type: application/pdf
Content-Disposition: attachment; filename="contrato-ejemplo.pdf"
Cache-Control: no-store
X-Request-Id: 3f0c...                     ← identificador para correlacionar logs
X-Pdf-Page-Count: 4
X-Conversion-Warnings: FONT_SUBSTITUTION_SUSPECTED          (solo si hay avisos)
X-Font-Substitution-Suspected: Calibri                       (solo si hay avisos)
<bytes del PDF>
```

Con cURL, `-o archivo.pdf` guarda el cuerpo. En Java:

```java
HttpResponse<byte[]> r = httpClient.send(HttpRequest.newBuilder(uri)
        .header("X-API-Key", apiKey)
        .header("Content-Type", "application/octet-stream")
        .POST(HttpRequest.BodyPublishers.ofByteArray(docxBytes)).build(),
    HttpResponse.BodyHandlers.ofByteArray());
byte[] pdf = r.body();
```

También puede ejecutar el flujo completo automatizado: `scripts\e2e.ps1` o `./scripts/e2e.sh`. El resultado queda en `out/`.

## 8. Ejecutar las pruebas

```bash
./mvnw verify                 # Windows: .\mvnw.cmd verify
```

Ejecuta **113 pruebas** sin Docker (unitarias y de integración de la API con un ONLYOFFICE **simulado**):

| Requisito | Pruebas |
|---|---|
| 1. Conversión DOCX → PDF | `ConversionApiTest.convertsBinaryDocxToPdf`, `convertsMultipartUpload` |
| 2. byte[] de entrada y salida | `DefaultDocumentConversionServiceTest.returnsPdfBytesAndPassesOriginalBytesUnmodified` |
| 3. Varias páginas | `convertsMultiPageContractWithTablesAndHeaders`, `PdfValidatorTest` |
| 4. Tablas y encabezados | `SampleDocxGenerator` + `FidelityTest.inspectorReadsSectionsAndUsedFonts`, `inspectEndpointReportsSectionsAndFonts` |
| 5. Documentos corruptos | `DocxValidatorTest` (truncado, datos corruptos, zip bomb, XXE, rutas…), `corruptDocumentIsRejectedWithoutCallingEngine` |
| 6. Errores de ONLYOFFICE | `OnlyOfficeClientTest.mapsOnlyOfficeJsonErrors` (-1…-10), 502/413, secreto incorrecto, `EngineUnavailableApiTest` |
| 7. Timeouts | `OnlyOfficeClientTest.slowEngineTimesOut`, `ConversionApiTest.slowConversionTimesOut` |
| 8. Validación de PDF | `PdfValidatorTest`, `invalidPdfFromEngineIsRejected` |
| 9. Conversiones consecutivas | `consecutiveConversionsDoNotDegrade` (25 seguidas) |
| 10. Conversiones simultáneas | `ConcurrentConversionApiTest`, `OverloadApiTest`, `ConversionLimiterTest` |

El simulador (`FakeOnlyOfficeServer`) verifica el contrato documentado de `/converter/from-file`: solo partes
`file`/`params`/`token`, JWT HS256 válido con `operation=converter` y sin `url`.

**Integración real con ONLYOFFICE (requiere Docker):**

```bash
./mvnw -Pintegration verify
```

`RealOnlyOfficeConversionIT` levanta `onlyoffice/documentserver:10.0.0` con Testcontainers, convierte los contratos de
ejemplo, valida los PDF y los tamaños de página (A4 vertical y Carta horizontal), y prueba conversiones consecutivas,
paralelas y con un secreto incorrecto. Los PDF quedan en `target/it-output/` para revisarlos a ojo. Sin Docker, estas
pruebas se omiten automáticamente.

**E2E con Docker Compose:** `scripts\e2e.ps1` / `./scripts/e2e.sh`.

Para regenerar los DOCX de ejemplo: ejecute la clase de prueba `pe.saeta.word2pdf.support.SampleDocxGenerator`
(desde el IDE o `java -cp target/test-classes;<classpath> ... SampleDocxGenerator .`).

## 9. Instalar fuentes adicionales

1. Copie los `.ttf`/`.otf`/`.ttc` **con licencia** en `fonts/` (o en el directorio indicado en `FONTS_DIR`).
2. `docker compose restart onlyoffice`. Con `GENERATE_FONTS=true`, ONLYOFFICE regenera su catálogo al arrancar.
   Para regenerarlo manualmente sin reiniciar: `docker compose exec onlyoffice documentserver-generate-allfonts.sh`.
3. Verifique:

```powershell
scripts\check-fonts.ps1 -Docx ruta\contrato.docx
```

```bash
./scripts/check-fonts.sh ruta/contrato.docx      # requiere jq
```

El script lista las fuentes del contenedor y marca como **[AUSENTE]** las fuentes del DOCX que ONLYOFFICE no tiene.
La imagen 10.0.0 **no incluye `fc-list`**, así que el script usa un inventario de archivos de `/usr/share/fonts` y
`core-fonts`. Detalles y advertencias de licencia en [fonts/README.md](fonts/README.md).

Fuentes que trae la imagen `onlyoffice/documentserver:10.0.0` (comprobado el 2026-11-17, 244 archivos):

- En `/usr/share/fonts/truetype/msttcorefonts/`: Arial, Times New Roman, Courier New, Verdana, Georgia, etc., y
  también **Calibri y Cambria**.
- En `core-fonts/crosextra/`: Carlito y Caladea, equivalentes métricos de Calibri y Cambria.
- **No incluye Aptos**, la fuente predeterminada de Word 365 desde 2023. Si los contratos usan Aptos, móntela en
  `fonts/`. Es la causa más probable de diferencias de paginación con documentos recientes.

## 10. Identificar errores de conversión

Los errores devuelven `application/problem+json` (RFC 9457) sin trazas internas:

```json
{"type":"urn:word2pdf:error:invalid-docx","title":"Unprocessable Content","status":422,
 "detail":"El archivo no es un documento DOCX válido o está dañado.","code":"INVALID_DOCX",
 "requestId":"9f6577b4-b5d0-4469-b735-f3dc182d1771"}
```

| HTTP | `code` | Causa | Acción |
|---|---|---|---|
| 400 | `EMPTY_DOCUMENT` | Cuerpo vacío | Enviar el archivo |
| 400 | `INVALID_REQUEST` | Falta la parte `file` o el multipart está mal formado | Revisar la petición |
| 401 | `UNAUTHORIZED` | Falta `X-API-Key` o es incorrecta | Revisar la API key |
| 413 | `DOCUMENT_TOO_LARGE` | Supera `APP_MAX_INPUT_SIZE`, o ONLYOFFICE respondió 413/-10 | Reducir el tamaño o subir el límite |
| 415 | `UNSUPPORTED_FORMAT` | No es DOCX: `.doc`, DOCX cifrado, `.docm`, PDF, RTF, xlsx… | Convertir a DOCX o quitar la contraseña |
| 422 | `INVALID_DOCX` | ZIP corrupto o truncado, zip bomb, falta `document.xml` | Abrir y volver a guardar en Word |
| 422 | `PASSWORD_PROTECTED` | ONLYOFFICE -5 | Quitar la contraseña |
| 422 | `CONVERSION_FAILED` | ONLYOFFICE -3/-9: contenido que el motor no puede procesar | Revisar el documento |
| 422 | `FIDELITY_CHECK_FAILED` | Modo estricto: fuentes sustituidas o tamaño de página distinto | Instalar las fuentes o revisar las secciones |
| 502 | `ENGINE_AUTH_ERROR` | ONLYOFFICE -8 o 401/403: el secreto JWT no coincide | Igualar `ONLYOFFICE_JWT_SECRET` en ambos contenedores |
| 502 | `ENGINE_ERROR` | ONLYOFFICE -1/-4/-6/-7 o respuesta inesperada | Revisar los logs de ONLYOFFICE |
| 502 | `INVALID_PDF_OUTPUT` | La respuesta no es un PDF válido | Revisar los logs de ONLYOFFICE |
| 503 | `ENGINE_UNAVAILABLE` | ONLYOFFICE no responde, o 5xx de su nginx (incluye `Retry-After`) | `docker compose ps`, readiness |
| 503 | `TOO_MANY_CONVERSIONS` | Sin cupo tras `APP_QUEUE_TIMEOUT` (incluye `Retry-After`) | Reintentar o aumentar los recursos |
| 504 | `CONVERSION_TIMEOUT` | Superó `ONLYOFFICE_CONVERSION_TIMEOUT`, o ONLYOFFICE -2 | Aumentar el timeout o simplificar el documento |

Diagnóstico:

```bash
docker compose logs word2pdf | grep <X-Request-Id>       # logs JSON (ECS) correlacionados
docker compose logs onlyoffice                           # ONLYOFFICE (nivel ONLYOFFICE_LOG_LEVEL)
docker compose exec onlyoffice sh -c 'ls -R /var/log/onlyoffice/documentserver'   # logs internos (la ruta varía según versión)
curl -H "X-API-Key: $APP_API_KEY" http://127.0.0.1:8080/actuator/metrics/docconv.conversion.duration
```

Los logs de `word2pdf` incluyen solo el request-id, los tamaños, la duración, el número de páginas, los códigos y los
nombres de fuentes. **Nunca** incluyen contenido del documento ni nombres de archivo.

---

## Fidelidad documental

### Medidas implementadas

1. **El documento no se modifica.** Los bytes recibidos se envían a ONLYOFFICE tal cual: sin Apache POI, sin HTML
   intermedio y sin LibreOffice. No se fija ningún parámetro de maquetación en la conversión, de modo que papel,
   márgenes, orientación, interlineado y estilos salen del propio DOCX.
2. **Control de tamaño y orientación de página.** Se leen los `w:sectPr/w:pgSz` de todas las secciones y cada página
   del PDF debe coincidir con alguna, con una tolerancia de 2 pt. Si no coincide → aviso `PAGE_SIZE_MISMATCH`
   (o 422 en modo estricto).
3. **Detección de fuentes sustituidas.** Se obtienen las fuentes realmente usadas (contenido, encabezados, pies, estilos
   referenciados con su cadena `basedOn`, `docDefaults` y fuentes del tema) y se comparan con las fuentes incrustadas
   en el PDF. Si una falta → aviso `FONT_SUBSTITUTION_SUSPECTED` con el nombre de la fuente (o 422 en modo estricto).
4. **Fuentes configurables** por volumen, con regeneración automática y el script de verificación `check-fonts`.
5. **Herramienta de QA `PdfComparisonTool`**, que compara con un PDF exportado desde Word:

```powershell
scripts\compare-pdf.ps1 -Reference contrato-word.pdf -Candidate contrato-onlyoffice.pdf `
    -Out reports\contrato -Anchors "Firma,EL CLIENTE,CLÁUSULA"
```

Informa de:
- diferencias en el **número de páginas** y las **dimensiones** de cada página;
- la **distribución visual**: render a N DPI, porcentaje de píxeles distintos por página y PNG de diferencias (rojo = solo en Word, azul = solo en ONLYOFFICE);
- el **desplazamiento de párrafos**: emparejamiento de líneas de texto y desplazamiento vertical en puntos, más las líneas que cambiaron de página (reflujo);
- la **posición de tablas y firmas**: página y posición de anclas de texto ("Firma", encabezados de tabla…).

Código de salida: `0` sin diferencias relevantes, `1` con diferencias y `2` por error de uso. El informe
`report.json` puede contener fragmentos de texto: trátelo como confidencial.

### Limitaciones

- Una validación estructural correcta (PDF válido, páginas, tamaños) **no garantiza** fidelidad visual.
- La detección de fuentes es heurística: compara nombres normalizados. Una fuente declarada en un estilo usado pero
  sin texto visible puede dar un falso positivo. Los nombres de fuentes sustitutas con métricas idénticas también se
  marcan, porque el nombre difiere.
- ONLYOFFICE y Word usan motores de maquetación distintos. Puede haber diferencias de partición de líneas, kerning,
  ajuste de tablas complejas, campos (`PAGE`, `NUMPAGES`, referencias cruzadas), cuadros de texto y objetos
  flotantes. Valide con la herramienta de comparación una muestra representativa de **sus** plantillas antes de pasar
  a producción.
- El render de la herramienta de QA usa PDFBox: en algunos casos puede diferir del renderizado de Acrobat.

---

## Seguridad

| Control | Implementación |
|---|---|
| Autenticación de clientes | `X-API-Key` (varias claves, ≥ 24 caracteres, comparación en tiempo constante). Los health checks son públicos; las métricas requieren clave |
| Tamaño máximo | Lectura acotada del cuerpo (no confía en `Content-Length`), límites multipart y verificación previa a la conversión |
| Formato real | Firma ZIP, directorio central, `[Content_Types].xml` con tipo principal WordprocessingML; rechazo de `.doc`, cifrados, `.docm`, plantillas y otros OOXML |
| ZIP / OOXML malicioso | Máximo de entradas, bytes descomprimidos **contados realmente**, ratio de compresión, rutas `..`/absolutas, entradas duplicadas, XML sin DTD ni entidades externas (anti-XXE) |
| PDF recibido | PDFBox en memoria (sin ficheros temporales), cabecera, ≥ 1 página, sin cifrado, tamaño máximo |
| Timeouts | Conexión, conversión, espera de cupo y health check configurables |
| Concurrencia | Semáforo justo con espera máxima; 503 + `Retry-After` |
| Datos confidenciales | Sin persistencia ni directorios públicos; multipart en memoria (`file-size-threshold` = máximo); `Cache-Control: no-store`; logs sin contenido ni nombres de archivo; se envía a ONLYOFFICE el título neutro `document.docx` |
| Errores | RFC 9457 sin trazas (`include-stacktrace: never`) |
| Secretos | Solo por variables de entorno; validación de longitud al arrancar; `toString()` enmascarado |
| Red | ONLYOFFICE sin puertos publicados y en una red interna sin salida a Internet; el servicio publicado por defecto solo en `127.0.0.1` |
| Contenedor | Usuario no root, `read_only`, `cap_drop: ALL`, `no-new-privileges`, `/tmp` en tmpfs |

En producción, publique el servicio detrás de un proxy inverso con **TLS** (nginx, Traefik, un API gateway) y rote las
API keys y el secreto JWT periódicamente. Cambiar `ONLYOFFICE_JWT_SECRET` exige reiniciar ambos contenedores.

---

## Almacenamiento temporal en ONLYOFFICE

**La conversión no ocurre solo en RAM.** ONLYOFFICE Docs escribe en disco durante la conversión:

- El archivo recibido y los resultados pasan por su almacenamiento de caché
  (`/var/lib/onlyoffice/documentserver/App_Data/cache/files`, dentro del volumen `onlyoffice_lib`), y el proceso
  `x2t` usa directorios temporales propios.
- **Retención por defecto** (según `default.json` del servidor ONLYOFFICE): `services.CoAuthoring.expire.files = 86400`
  segundos (24 h), con limpieza periódica `expire.filesCron = "00 00 */1 * * *"` (cada hora). Además, el contenedor
  ejecuta `documentserver-flush-cache` al arrancar.
- **Configuración de retención:** [onlyoffice/local-production-linux.json](onlyoffice/local-production-linux.json)
  pide reducir la retención a **600 s** y la limpieza a **cada 5 minutos**. Se monta como capa de configuración de
  node-config (los servicios corren con `NODE_ENV=production-linux` y `NODE_CONFIG_DIR=/etc/onlyoffice/documentserver`).

**Verificado el 2026-11-17 con la imagen 10.0.0:**

- Cada conversión por `/converter/from-file` deja **`origin.docx` (el Word recibido) y `output.pdf`** en
  `cache/files/data/conv_<id>/`.
- **La retención de 600 s no es fiable.** Una conversión seguía en disco 23 minutos después; otras se borraron en
  unos 20 minutos. No hay que depender de esta configuración para la confidencialidad.
- El conversor `x2t` crea temporales en `/tmp` del contenedor y los borra al terminar; no quedaron restos.

**Por eso `docker-compose.yml` monta la caché como `tmpfs`** (1 GB en RAM):

- Las copias **nunca se escriben en disco** y desaparecen al reiniciar o recrear el contenedor (comprobado:
  0 archivos tras `docker compose restart onlyoffice`).
- Mientras el contenedor está en marcha, las copias pueden seguir en RAM. Para vaciarlas en cualquier momento:
  `docker compose exec onlyoffice sh -c 'rm -rf /var/lib/onlyoffice/documentserver/App_Data/cache/files/data/conv_*'`.
- El `tmpfs` cuenta para el límite de memoria de ONLYOFFICE (`ONLYOFFICE_MEMORY`, 4 GB por defecto).

Para inspeccionar la caché:

```bash
docker compose exec onlyoffice sh -c 'find /var/lib/onlyoffice/documentserver/App_Data/cache/files -type f'
```

Para eliminar todos los datos residuales: `docker compose down -v` (borra los volúmenes).

---

## Rendimiento y configuración

- Un único `java.net.http.HttpClient` con keep-alive reutiliza las conexiones hacia ONLYOFFICE.
- ONLYOFFICE se ejecuta como servicio permanente: no se reinicia por cada conversión.
- `APP_MAX_CONCURRENT` (por defecto 4) protege la CPU y la RAM. Ajústelo según los núcleos asignados a ONLYOFFICE.
- Hilos HTTP acotados (`APP_HTTP_THREADS`, 32 por defecto) para limitar la memoria retenida por cuerpos en tránsito
  (≈ `APP_MAX_INPUT_SIZE` + PDF por petición).
- Métricas en `/actuator/metrics`: `docconv.conversion.duration` (tags `engine`, `outcome`),
  `docconv.conversion.in_flight`, `docconv.conversion.rejected` y `docconv.input.size`.
- Sin caché de documentos: cada conversión usa una clave (`key`) aleatoria, así que ONLYOFFICE no reutiliza resultados.

---

## Integración con la API de ONLYOFFICE: notas técnicas

Basadas en la [documentación oficial de `/converter/from-file`](https://api.onlyoffice.com/docs/docs-api/additional-api/conversion-api/request-from-file)
(10.0):

- `multipart/form-data` con, como mucho, las partes `file`, `params` y `token`. Cualquier otra parte provoca el error -7.
- Con JWT activo, **los parámetros se toman solo del token**. El servicio envía `file` + `token`, y el token (HS256)
  contiene `filetype=docx`, `outputtype=pdf`, `key` (UUID aleatorio), `title=document.docx`,
  **`operation=converter`**, `iat` y `exp` (5 min). **No incluye `url`.**
- Transporte alternativo configurable: `ONLYOFFICE_JWT_TRANSPORT=HEADER` envía `Authorization: Bearer` con los
  parámetros en el claim `payload`, como indica la documentación de tokens en cabecera. El modo por defecto es `FORM_PART`.
- Respuesta: binario (PDF) si la conversión tuvo éxito; JSON `{"error": n}` si no. El cliente distingue ambos casos
  por `Content-Type` y por el primer byte. Las respuestas `413` y `5xx` del nginx de ONLYOFFICE se tratan aparte.
- Conversión síncrona (sin `async`), con timeout del lado del cliente.
- Desde 10.0, JWT viene activado por defecto en ONLYOFFICE.

> **Verificado el 2026-11-17 contra `onlyoffice/documentserver:10.0.0`:** el transporte por defecto `FORM_PART`
> (JWT en la parte multipart `token`) funciona tanto en `RealOnlyOfficeConversionIT` (ONLYOFFICE con
> `JWT_ENABLED=true` y el resto de opciones por defecto) como en Docker Compose (`JWT_HEADER=Authorization`,
> `JWT_IN_BODY=false`). ONLYOFFICE devolvió el PDF binario directamente en la respuesta, y un token firmado con
> otro secreto se rechaza con `{"error":-8}`. El transporte `HEADER` no se ha probado contra el servidor real.

---

## Estado de verificación

| Verificación | Estado |
|---|---|
Última verificación: 2026-11-17, Windows 11, JDK 21.0.10, Docker Engine 29.9.2.

| Verificación | Estado |
|---|---|
| 113 pruebas unitarias e integración de API con ONLYOFFICE simulado (`mvnw verify`) | ✅ 0 fallos |
| Arranque del JAR: fallo controlado sin secreto, liveness 200, readiness 503 sin motor, 401 sin API key, 422 DOCX corrupto, 503 motor caído, `/inspect` | ✅ Ejecutado manualmente |
| CLI `PdfComparisonTool` mediante `PropertiesLauncher` | ✅ Ejecutada |
| `RealOnlyOfficeConversionIT` (Testcontainers + ONLYOFFICE 10.0.0), 6 pruebas | ✅ 0 fallos, transporte JWT `FORM_PART` |
| Construcción de la imagen `Dockerfile` | ✅ |
| `scripts/e2e.ps1` con Docker Compose (contenedor `read_only`, usuario `app`, `cap_drop: ALL`) | ✅ Contrato de ejemplo de 4 páginas en unos 1,3 s |
| Logs sin nombres de archivo ni contenido | ✅ Revisado tras el e2e |
| Retención de caché con `local-production-linux.json` | ❌ No fiable; se sustituye por `tmpfs` |
| Caché en `tmpfs` | ✅ Se vacía al reiniciar; e2e correcto con `tmpfs` activo |
| Fuentes montadas en `fonts/` | ✅ Fuente de prueba registrada tras reiniciar e incrustada en el PDF |
| Fidelidad frente a Word con 1 documento real (pagaré, 4 páginas) | ✅ 4 = 4 páginas, 149 de 151 líneas en su sitio (desplazamiento máximo 0,1 pt), 1 diferencia de salto de línea |
| Fidelidad con una muestra amplia de contratos reales | ⏳ Pendiente: probar 5-10 contratos variados (tablas, Aptos, etc.) |

Licenciamiento: ver [LICENSING.md](LICENSING.md).
