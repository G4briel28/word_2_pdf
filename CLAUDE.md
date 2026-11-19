# CLAUDE.md: contexto del proyecto word2pdf

Documentación para usuarios: [README.md](README.md). Licencias: [LICENSING.md](LICENSING.md).
Idioma: documentación y mensajes en **español**; identificadores de código en inglés.

## Qué es

Microservicio Java 21 + Spring Boot 4.1.2 (Maven, con wrapper `mvnw`) que convierte contratos **DOCX → PDF** usando
**ONLYOFFICE Docs Community 10.0.0** (`onlyoffice/documentserver:10.0.0`) mediante `POST /converter/from-file`
(multipart, sin publicar archivos por URL). Recibe `byte[]` y devuelve `byte[]`, sin guardar nada en disco.
**Prioridad absoluta: fidelidad visual del PDF respecto al Word.**

Restricciones del cliente (no romperlas):
- No usar Aspose, librerías comerciales, Microsoft Office, LibreOffice, Apache POI para reconstruir el DOCX ni HTML intermedio.
- No modificar el DOCX antes de convertirlo. No fijar parámetros de maquetación en la conversión.
  **Única excepción aprobada (2026-11-18):** `compat/HyphenationCompatibility` quita `<w:autoHyphenation/>` de una
  copia en memoria cuando `pPrDefault` declara `<w:suppressAutoHyphens/>` (Word no divide palabras y ONLYOFFICE 10 sí).
  Lo indica el aviso `HYPHENATION_ADJUSTED` y se desactiva con `APP_COMPAT_ALIGN_HYPHENATION=false`. No añadir más
  ajustes de este tipo sin aprobación expresa.
- No registrar contenido ni nombres de archivo de contratos en logs. No persistir documentos.
- Secretos solo por variables de entorno. ONLYOFFICE nunca expuesto públicamente.
- No afirmar fidelidad del 100 % ni declarar pruebas exitosas sin haberlas ejecutado.

## Arquitectura (`src/main/java/pe/saeta/word2pdf/`)

| Paquete | Clases clave |
|---|---|
| `controller` | `ConversionController`: `POST /api/v1/conversion/docx-to-pdf` (octet-stream), `/docx-to-pdf/multipart`, `/docx/inspect` |
| `service` | `DocumentConversionService` (contrato pedido por el cliente), `DefaultDocumentConversionService` (orquesta), `ConversionEngine` (puerto para cambiar de motor), `ConversionLimiter` (semáforo) |
| `client` | `OnlyOfficeClient` (JDK HttpClient, multipart `file` + `token`), `OnlyOfficeJwtSigner` (nimbus HS256, `operation=converter`, sin `url`), `OnlyOfficeErrorCode` (-1…-10), `OnlyOfficeHealthIndicator` |
| `config` | Records `@ConfigurationProperties` (`onlyoffice.*`, `app.conversion.*`, `app.security.*`), `ApiKeyAuthFilter` (`X-API-Key`), `RequestIdFilter` |
| `compat` | `HyphenationCompatibility`: único ajuste en memoria del DOCX (división de palabras, ver restricciones) |
| `exception` | `ErrorCode` → HTTP, `ConversionException`, `GlobalExceptionHandler` (ProblemDetail RFC 9457) |
| `validation` | `DocxValidator` (ZIP/OOXML, anti zip-bomb, anti-XXE), `PdfValidator` (PDFBox 3.0.8 en memoria), `BoundedBodyReader` |
| `fidelity` | `DocxLayoutInspector` (tamaños de página y fuentes del DOCX), `FidelityChecker` (avisos `PAGE_SIZE_MISMATCH` / `FONT_SUBSTITUTION_SUSPECTED`) |
| `quality` | `PdfComparisonTool`: CLI de QA que compara un PDF de Word con el de ONLYOFFICE |

Notas de Spring Boot 4: Jackson 3 (`tools.jackson.*`), health en `org.springframework.boot.health.contributor`,
starters `spring-boot-starter-webmvc` / `-webmvc-test`, Testcontainers 2.x (`testcontainers-junit-jupiter`).

## Comandos

```powershell
.\mvnw.cmd verify                      # 127 pruebas sin Docker (ONLYOFFICE simulado: FakeOnlyOfficeServer)
.\mvnw.cmd -Pintegration verify        # + RealOnlyOfficeConversionIT con Testcontainers (requiere Docker)
scripts\generate-env.ps1               # crea .env con secretos (no versionado)
docker compose up -d --build           # servicio en 127.0.0.1:8080, ONLYOFFICE en red interna
scripts\e2e.ps1                        # compose + conversión de samples\contrato-ejemplo.docx → out\
scripts\check-fonts.ps1 -Docx x.docx   # fuentes del contenedor vs fuentes del DOCX
scripts\compare-pdf.ps1 -Reference word.pdf -Candidate onlyoffice.pdf
```

Desarrollo con el JDK local: `docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d onlyoffice`
(publica ONLYOFFICE en `127.0.0.1:8081`) y `ONLYOFFICE_URL=http://localhost:8081` + `.\mvnw.cmd spring-boot:run`.

Los DOCX de prueba se generan con `src/test/java/.../support/SampleDocxGenerator.java` (OOXML escrito a mano, contenido ficticio).

## Estado al 2026-11-18

Verificado **con Docker** (Engine 29.9.2). Detalle en la tabla "Estado de verificación" del README:
- `mvnw verify`: 127 pruebas, 0 fallos. `-Pintegration verify`: además, 7 pruebas reales con ONLYOFFICE 10.0.0, 0 fallos.
- 7 plantillas CUMPLO (`archivos de prueba/PLANTILLAS CUMPLO`, con PDF de Word): el cuerpo usa **Arial Nova**, que no
  está en el servidor (en la PC solo existe como fuente de la nube de Office). Sin ella, los contratos pierden una
  página. Con Arial Nova (prueba local) y el ajuste de división: mismas páginas en las 7, entre el 96 % y el 100 % de
  líneas en su sitio (un anexo, el 73 %). Se comprará la licencia de servidor si se despliega. Aptos solo aparece
  declarada (tema, hueco `eastAsia`, espacios): no hace falta.
- ONLYOFFICE 10 usa el `w:lang` del DOCX, pero solo divide palabras en `es-ES`: su lista de idiomas con división está
  fija en `sdkjs` (`spellcheckGetLanguages`) y no incluye `es-PE`. Ampliar `es_ES.json` o montar `dictionaries/es_PE`
  no tiene efecto (probado). Limitación documentada; no parchear ONLYOFFICE.
- El transporte JWT `FORM_PART` funciona con 10.0.0 (Testcontainers y Compose). `HEADER` no se ha probado contra el servidor real.
- Imagen Docker, e2e con Compose, contenedor `read_only` y logs sin contenido: correctos.
- Caché: cada conversión deja `origin.docx` + `output.pdf` en `cache/files/data/conv_<id>/`. La retención de
  `local-production-linux.json` **no es fiable**, así que `docker-compose.yml` monta esa ruta como **`tmpfs`** (verificado).
- Fuentes: la imagen no tiene `fc-list`. Trae Calibri y Cambria, pero **no Aptos**. Las fuentes montadas en `fonts/`
  se registran al reiniciar y se incrustan en el PDF.
- Fidelidad frente a Word con 1 pagaré real: 4 = 4 páginas, 149 de 151 líneas coinciden, desplazamiento máximo 0,1 pt.
- Arreglos hechos: `FakeOnlyOfficeServer` (medición de concurrencia que hacía fallar `OverloadApiTest` de forma
  intermitente) y `PdfComparisonTool` (ahora empareja líneas completas; antes, fragmentos de texto).
- La automatización COM de Word se cuelga en esta PC: el usuario exporta los PDF de referencia a mano
  (Archivo → Exportar → Crear documento PDF/XPS).

## PENDIENTE

1. **Fidelidad con más contratos reales:** ya hay 8 (pagaré + 7 CUMPLO). Calibrar `--pixel-threshold`: con un 2 %,
   el pagaré se marcó por una sola diferencia de salto de línea. Los contratos reales NO deben subirse al repositorio.
2. **Falsos positivos de `FONT_SUBSTITUTION_SUSPECTED`:** salta por fuentes declaradas sin texto visible (Aptos en el
   tema, en `eastAsia` o en runs de solo espacios). Propuesto al usuario; no implementado.
3. **Licencia de Arial Nova** (Monotype, uso en servidor e incrustación en PDF) antes de desplegar con las plantillas
   CUMPLO. En local, `fonts/` ya tiene ArialNova.ttf, -Bold, -Italic y -BoldItalic, de la versión 1.05 de Microsoft
   (zip del usuario, con métricas idénticas a las de Word). Su licencia solo permite usarla dentro del producto de
   Microsoft: no llevarla a producción.
4. En alguna plantilla CUMPLO aparece la fuente "Arial Nava" (errata): corregirla en la plantilla.
5. Opcional: probar `ONLYOFFICE_JWT_TRANSPORT=HEADER` contra el servidor real. Valorar `tmpfs` también para `/tmp` de
   ONLYOFFICE (requiere `exec`, porque `/tmp/.cache` carga módulos nativos).

Mejoras opcionales, no pedidas aún: TLS mediante proxy inverso, endpoint Prometheus, informe de licencias con
`license-maven-plugin`, CI en GitHub Actions (`mvnw verify` sin Docker).
