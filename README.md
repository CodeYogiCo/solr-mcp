# Solr MCP Server (http4k + MCP Java SDK + SolrJ)

A Model Context Protocol (MCP) server that lets AI assistants (Claude Desktop, Claude
Code, MCP Inspector) search, index, and manage **Apache Solr** collections.

Built on a **100% free-for-commercial-use** stack:

| Layer | Library | License |
|-------|---------|---------|
| Protocol | [MCP Java SDK](https://github.com/modelcontextprotocol/java-sdk) (`io.modelcontextprotocol.sdk:mcp-core`) | MIT |
| HTTP edge | [http4k](https://www.http4k.org) core + Jetty + realtime (SSE) | Apache 2.0 |
| Solr access | Apache SolrJ | Apache 2.0 |
| Language | Kotlin | Apache 2.0 |

> This deliberately does **not** use `http4k-mcp-server`, which is a commercially
> licensed http4k **Pro** module. The HTTP transport here is a small, free port of the
> MCP SDK's own SSE transport onto http4k — see
> [`Http4kSseServerTransportProvider`](src/main/kotlin/co/codeyogi/solrmcp/transport/Http4kSseServerTransportProvider.kt).

## Tools

| Tool | Description |
|------|-------------|
| `search` | Full-text search with filters, facets, sorting, pagination |
| `index-json-documents` / `index-csv-documents` / `index-xml-documents` | Index documents from JSON / CSV / XML |
| `list-collections` | List all collections |
| `get-collection-stats` | Index statistics + document counts |
| `check-health` | Ping + doc count for a collection |
| `create-collection` | Create a collection (configSet `_default`, 1 shard, rf 1 by default) |
| `get-schema` | Retrieve the schema |
| `add-fields` / `add-field-types` | Additive schema changes |

## Transports

Selected by the `PROFILES` env var (default `stdio`):

- **`stdio`** — MCP SDK stdio transport over System.in/out (for Claude Desktop). http4k is dormant.
- **`http`** — http4k + Jetty serving the MCP HTTP+SSE transport (for MCP Inspector / remote):
  - `GET /sse` opens the event stream and returns an `endpoint` event,
  - `POST /message?sessionId=…` delivers JSON-RPC messages,
  - `GET /health` for probes.

## Build & run

Requires JDK 21+ and Docker (for Solr).

```bash
# Start a local Solr (single-node SolrCloud)
docker compose up -d            # http://localhost:8983

# STDIO (default)
./gradlew run
# or the fat jar:
./gradlew shadowJar
java -jar build/libs/solr-mcp-0.1.0-all.jar

# HTTP on :8080
PROFILES=http java -jar build/libs/solr-mcp-0.1.0-all.jar
```

### Configuration

| Env var | Default | Notes |
|---------|---------|-------|
| `SOLR_URL` | `http://localhost:8983/solr/` | Solr base URL |
| `PROFILES` | `stdio` | `stdio` or `http` |
| `PORT` | `8080` | HTTP mode port |
| `OAUTH2_ISSUER_URI` | _(unset)_ | If set, HTTP mode validates bearer JWTs against this issuer's JWKS |
| `MCP_CORS_ALLOWED_ORIGINS` | `http://localhost:6274,http://127.0.0.1:6274` | CORS allow-list (MCP Inspector) |

### Claude Desktop (STDIO)

```json
{
  "mcpServers": {
    "solr-mcp": {
      "command": "java",
      "args": ["-jar", "/absolute/path/to/solr-mcp-0.1.0-all.jar"],
      "env": { "SOLR_URL": "http://localhost:8983/solr/" }
    }
  }
}
```

## Tests

Functional tests run the services and the MCP tool layer against a **real Solr brought
up via docker compose** (Testcontainers `ComposeContainer`, see
[`src/test/resources/solr-compose.yaml`](src/test/resources/solr-compose.yaml)).

```bash
./gradlew test
```

On macOS with Docker Desktop you may need to point Testcontainers at the daemon socket:

```bash
DOCKER_HOST=unix://$HOME/.docker/run/docker.sock ./gradlew test
```

CI (`.github/workflows/ci.yml`) runs the full build + functional tests on every push/PR;
`release.yml` publishes the fat jar on `v*` tags.

## Project layout

```
src/main/kotlin/co/codeyogi/solrmcp/
  Main.kt                         entrypoint; stdio/http branch
  config/                         SolrClient factory, JSON
  search/ indexing/ collection/ schema/   the four services (SolrJ logic)
  mcp/                            tool registration (SyncToolSpecification) + server
  transport/                      http4k SSE transport bridge (Strategy B)
  http/                           http4k routes, Jetty, JWT + CORS filters
```

## License

Apache 2.0.
