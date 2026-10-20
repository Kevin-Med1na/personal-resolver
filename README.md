# Persona Resolver

Servicio backend en **Spring Boot** que resuelve información básica a partir de un
documento de identificación colombiano. Recibe un tipo y número de documento,
consulta la fuente externa que corresponde y normaliza la respuesta a un modelo
común.

| Documento | Fuente | Resultado |
|---|---|---|
| `CEDULA` | RUI (Ventanilla Social, DNP) | `PersonBasicData` |
| `NIT` | RUES | `CompanyBasicData` |

El frontend vive en otro repositorio (`personal-resolver-frontend`).

---

# Requisitos de ejecución

- **Salida a internet desde Colombia.** Ventanilla Social (RUI) bloquea las IPs
  extranjeras, así que el backend no puede consultar cédulas desde un servidor en
  otro país. RUES no tiene esa restricción.
- **PostgreSQL** accesible por JDBC.
- Si un frontend en la nube lo consume, el backend necesita una dirección pública:
  un proxy inverso o un túnel delante del contenedor.

---

# Arquitectura

## Despliegue

```text
Cliente (por ejemplo, el frontend)
   │  /api/*
   ▼
Proxy inverso o túnel (opcional)
   │
   ▼
Docker: persona-resolver-app (127.0.0.1:8099), con salida a internet desde Colombia
   │
   ├── CEDULA ──► RuiAdapter ──► Ventanilla Social
   ├── NIT    ──► RuesAdapter ─► RUES
   └── historial ──────────────► PostgreSQL
```

El contenedor publica solo en `127.0.0.1`: nada entra desde la red salvo lo que
deje pasar el proxy o el túnel.

## Código

Arquitectura hexagonal con DDD ligero. El caso de uso depende de puertos del
dominio, no de HTTP ni de Spring.

```text
infrastructure / rest
├── PersonController   POST /api/lookup
└── HealthController   GET  /api/health
        │
        ▼
application
└── ResolveDocumentUseCase  (DocumentResolverApplicationService)
        │
        ▼
domain
├── DocumentQuery, DocumentValidator
└── ports: PersonDataSource, CompanyDataSource
        ▲
        │
infrastructure / adapters
├── RuiAdapter  ── RuiService  ── Ventanilla Social
├── RuesAdapter ── RuesService ── RUES
└── DocumentPersistenceAdapter ── PostgreSQL
```

Cualquier cambio de estrategia de consulta entra como un adapter nuevo detrás del
puerto correspondiente; el caso de uso no se toca.

---

# API

## POST `/api/lookup`

```http
POST /api/lookup
Content-Type: application/json
```

```json
{ "documentType": "CEDULA", "documentNumber": "1234567890" }
```

```json
{ "documentType": "NIT", "documentNumber": "900351983" }
```

Respuesta `200` para una cédula:

```json
{
  "documentType": "CEDULA",
  "documentNumber": "1234567890",
  "source": "RUI",
  "status": "FOUND",
  "entityType": "PERSON",
  "person": {
    "documentNumber": "1234567890",
    "fullName": "CARLOS ANDRES RUIZ DIAZ",
    "firstName": "CARLOS ANDRES",
    "lastName": "RUIZ DIAZ",
    "gender": "Masculino",
    "state": "BOLÍVAR",
    "city": "CARTAGENA DE INDIAS",
    "address": null,
    "age": 28,
    "ruiGroup": "D",
    "ruiLevel": "D09",
    "incomeGroup": "Ingreso observado y estimado",
    "municipalityCode": "13001"
  },
  "message": "Consulta realizada con éxito",
  "consultCount": 3,
  "firstConsultDate": "2026-09-15T21:08:40Z",
  "previousConsultDate": "2026-10-19T14:02:11Z"
}
```

Para un NIT, `source` es `RUES`, `entityType` es `COMPANY` y los datos van en
`company` (`companyName`, `businessType`, `state`, `city`). Los campos `null` del
primer nivel (`person` o `company`, `previousConsultDate`) se omiten.

| Campo | Significado |
|---|---|
| `consultCount` | Veces consultado **con esta misma información**. Se reinicia cuando los datos cambian. |
| `firstConsultDate` | Primera vez que se vio el documento, en UTC. |
| `previousConsultDate` | Consulta inmediatamente anterior a la actual, en UTC; `null` (omitido) si es la primera. |

### Errores

Todos responden con el mismo formato:

```json
{
  "timestamp": "2026-10-20T15:00:00Z",
  "status": 404,
  "error": "Not Found",
  "message": "No se encontró información para la cédula consultada."
}
```

| HTTP | Cuándo |
|---|---|
| `400` | Documento inválido o cuerpo mal formado. |
| `404` | La fuente respondió y no hay información para el documento. |
| `502` | La fuente respondió algo inesperado (cambio de contrato, error HTTP). |
| `503` | La fuente no respondió a tiempo. El resultado es inconcluyente: se puede reintentar. |

El frontend no muestra estos mensajes tal cual, porque nombran las fuentes; usa
los suyos según el código.

## GET `/api/health`

Responde `{"status":"UP"}` sin consultar ninguna fuente ni la base de datos. El
frontend lo usa para saber si el backend está disponible: contra
`127.0.0.1:8099` prueba el contenedor; contra la dirección pública, toda la
cadena.

---

# Fuentes externas

## RUI — Personas

Se usa el endpoint JSON que llama la funcionalidad **Consulta RUI** de Ventanilla
Social. No se usa navegador ni scraping.

```text
GET  https://ventanillasocial.dnp.gov.co/                 → sesión, cookies y nonce
POST https://ventanillasocial.dnp.gov.co/Home/ObtenerDatosRUI
     Content-Type: application/x-www-form-urlencoded
     X-Rui-Nonce: <nonce de la sesión>

     pNumDoc=<número>&pTipDoc=3                             (3 = cédula de ciudadanía)
```

**Nonce.** La landing trae un nonce por sesión incrustado en su JavaScript
(`var _ruiPubNonce='<32 hex>';`) y el portal lo envía en la cabecera
`X-Rui-Nonce`. Sin él, `ObtenerDatosRUI` responde `{"ok":false}` a cualquier
cédula, que es lo mismo que responde cuando la cédula no existe. Si la landing
deja de traer el nonce, el backend responde `502` diciendo que el portal cambió,
en vez de un `404` falso.

**Sesiones y nodos.** Ventanilla Social está detrás de un balanceador KEMP y no
todos sus nodos resuelven consultas. `RuiService` reutiliza durante 10 minutos la
última sesión que funcionó (cookies + nonce) y, si no hay una, prueba hasta 8
sesiones nuevas dentro de un presupuesto de 12 s. Agotar los intentos da `404`;
agotar el tiempo da `503`.

Respuesta de la fuente:

```json
{
  "ok": true,
  "grupoIngresos": "Solo ingreso estimado",
  "grupRui": "C",
  "nivelRui": "C01",
  "municipio": "TURBACO",
  "departamento": "BOLÍVAR",
  "codMpio": "13836",
  "nombre": "JUAN CARLOS PEREZ GOMEZ",
  "sexo": "Masculino",
  "edad": 20
}
```

## RUES — Empresas

Se usa el endpoint JSON del portal RUES, con una sesión HTTP independiente por
consulta.

```text
GET  https://app-antiguoprd.rues.org.co/RM                 → cookies y __RequestVerificationToken
POST https://app-antiguoprd.rues.org.co/Home/ConsultaNIT_json
     __RequestVerificationToken=<token>&txtNIT=<nit>&txtCI=&txtDV=
```

De la respuesta se usan `razon_social`, `categoria_matricula`, `municipio` y
`estadoRM`. `records = 0` significa que no hay información (`404`).

---

# Persistencia

PostgreSQL. El esquema lo mantiene Hibernate (`ddl-auto=update`).

- `persona` y `empresa`: estado actual de cada documento, únicos por
  `(document_type, document_number)`.
- `consulta_documento`: historial con `raw_json_response` (JSONB) y
  `response_hash` (SHA-256).

Al llegar una consulta se calcula el hash de la respuesta sin los metadatos
(`consultCount`, `firstConsultDate`, `previousConsultDate`):

- **Mismo hash que el último registro:** no se crea fila; se incrementa
  `consult_count` y se actualiza `last_consult_date`.
- **Hash distinto:** se crea un registro nuevo con `consult_count = 1` y se
  actualiza `persona` o `empresa`.

Las fechas se guardan en UTC (`LocalDateTime.now(ZoneOffset.UTC)`) y se exponen
como `OffsetDateTime` en UTC, para que el cliente las convierta a su hora local.

El pool de conexiones se vacía tras 2 minutos sin uso (`minimum-idle=0`), para que
un PostgreSQL serverless pueda apagar su compute entre consultas.

---

# Ejecución con Docker

```bash
cp .env.example .env            # completar la conexión a PostgreSQL
docker compose up -d --build
curl http://127.0.0.1:8099/api/health      # {"status":"UP"}
```

El contenedor tiene `restart: unless-stopped`: vuelve a levantarse cuando arranca
Docker. Si se detiene a mano (`docker compose stop`), queda detenido hasta
`docker compose start`.

Para actualizar:

```bash
git pull
docker compose up -d --build
```

Si se publica el puerto con un proxy o un túnel, ese puerto debe ser solo de este
backend: con el contenedor detenido, cualquier otro proceso que escuche en él
quedaría publicado en su lugar.

---

# Configuración

Toda la configuración entra por variables de entorno (`.env`, ver `.env.example`).
`docker-compose.yml` declara las obligatorias con `:?` y falla si falta alguna.

| Variable | Uso |
|---|---|
| `SPRING_DATASOURCE_URL` | URL JDBC de PostgreSQL. Obligatoria. |
| `SPRING_DATASOURCE_USERNAME` / `SPRING_DATASOURCE_PASSWORD` | Credenciales de la base. Obligatorias. |
| `APP_PORT` | Puerto del host en `127.0.0.1` donde se publica el contenedor (por defecto `8099`). |
| `APP_SCRAPER_TIMEOUT_MS` | Timeout de las fuentes externas (por defecto `30000`). |
| `APP_SOURCES_RUI_USER_AGENT` / `APP_SOURCES_RUES_USER_AGENT` | User-Agent de cada fuente. |

---

# Desarrollo

```bash
./mvnw spring-boot:run          # Linux / macOS
.\mvnw.cmd spring-boot:run      # Windows
./mvnw test
```

Sin variables de entorno, la aplicación usa un PostgreSQL en `localhost:5432` con
`root` / `rootpassword`. `PersonaResolverApplicationTests.contextLoads` necesita
esa base; el resto de las pruebas no.

Ejemplos contra el contenedor (documentos ficticios):

```bash
curl -X POST http://127.0.0.1:8099/api/lookup \
  -H "Content-Type: application/json" \
  -d "{\"documentType\":\"CEDULA\",\"documentNumber\":\"1234567890\"}"

curl -X POST http://127.0.0.1:8099/api/lookup \
  -H "Content-Type: application/json" \
  -d "{\"documentType\":\"NIT\",\"documentNumber\":\"900351983\"}"
```

---

# Diagnóstico

Si las cédulas devuelven `404` en todos los casos, RUI está respondiendo
`ok:false` y el backend descarta ese cuerpo. `scripts/diagnostico-rui.ps1` repite
la consulta desde una máquina con IP colombiana y muestra qué responde la fuente,
sin imprimir datos personales. También revisa el HTML y el JavaScript del portal
para ver cómo consulta hoy: endpoint, parámetros, tipo de documento, nonce,
captcha.

```powershell
powershell -ExecutionPolicy Bypass -File scripts\diagnostico-rui.ps1 -Cedula <cedula>
```

Probar desde fuera de Colombia no sirve para RUI: el geobloqueo responde distinto.

---

# Seguridad

Las consultas contienen documentos de identificación.

- No registrar números de documento completos ni tokens en los logs.
- No almacenar cookies de sesión, tokens antiforgery ni nonces.
- Secretos solo por variables de entorno; `.env` no se versiona.
- `/api/lookup` no tiene autenticación: si se publica, cualquiera puede consultar.
  Para restringirlo hay que hacerlo en el backend o en el proxy.

---

# Riesgos y limitaciones

- **Fuentes no oficiales:** RUI y RUES se consultan con los endpoints internos de
  sus portales, que no son APIs públicas ni versionadas. Pueden cambiar sin aviso
  (como pasó con el nonce de RUI) y cada cambio exige revisar la petición real del
  navegador.
- **RUI ambiguo:** `ok:false` significa tanto "no existe" como "este nodo no
  resuelve", así que una cédula inexistente cuesta los 8 intentos antes del `404`.

---

# Historial de estrategia

- **ADRES con scraping:** descartado por JavaScript dinámico, CAPTCHA y selectores
  cambiantes.
- **RUI y RUES con Playwright:** reemplazado por HTTP directo a los endpoints JSON
  de cada portal.
- **Backend en la nube + relay RUI con IP colombiana:** el backend delegaba las
  cédulas en una segunda instancia con IP colombiana, protegida con token. Se
  retiró al pasar a ejecutar todo el backend con salida desde Colombia.
- **Nonce de RUI (octubre de 2026):** Ventanilla Social empezó a exigir
  `X-Rui-Nonce` y todas las cédulas pasaron a dar `404`. Se diagnosticó con
  `scripts/diagnostico-rui.ps1` y se corrigió en `RuiService`.
