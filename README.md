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

## Install

### Homebrew (easiest)

```bash
# one-off install from this repo's formula
brew install https://raw.githubusercontent.com/CodeYogiCo/solr-mcp/main/Formula/solr-mcp.rb

# or as a tap
brew tap codeyogico/solr-mcp https://github.com/CodeYogiCo/solr-mcp
brew install solr-mcp
```

Then:

```bash
solr-mcp                    # STDIO (default)
PROFILES=http solr-mcp      # HTTP on :8080
```

### Docker (Jib image)

```bash
# STDIO (Claude Desktop)
docker run -i --rm -e SOLR_URL=http://host.docker.internal:8983/solr/ \
  ghcr.io/codeyogico/solr-mcp:latest

# HTTP
docker run -p 8080:8080 --rm -e PROFILES=http \
  -e SOLR_URL=http://host.docker.internal:8983/solr/ \
  ghcr.io/codeyogico/solr-mcp:latest
```

The image is published to GHCR by [`docker.yml`](.github/workflows/docker.yml) on
every push to `main` and on `v*` tags.

> **Note:** GHCR packages are **private** by default. To let anyone `docker pull`
> without authenticating, make it public once: GitHub → CodeYogiCo → Packages →
> `solr-mcp` → *Package settings* → **Change visibility → Public**. While private,
> pull after `echo $TOKEN | docker login ghcr.io -u <user> --password-stdin`.

Build the image yourself (no Dockerfile needed — Jib):

```bash
./gradlew jibDockerBuild     # -> local Docker: ghcr.io/codeyogico/solr-mcp:<version>
./gradlew jib                # build + push (multi-arch amd64/arm64) to the registry
```

## Build & run from source

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

## Using it with an MCP client

Make sure Solr is running and reachable (`docker compose up -d` starts one on
`localhost:8983`). Then point your client at the server.

### Claude Desktop (STDIO)

Edit `claude_desktop_config.json` (macOS:
`~/Library/Application Support/Claude/claude_desktop_config.json`) and restart Claude.

**Via Homebrew install:**

```json
{
  "mcpServers": {
    "solr-mcp": {
      "command": "solr-mcp",
      "env": { "SOLR_URL": "http://localhost:8983/solr/" }
    }
  }
}
```

**Via the jar:**

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

**Via Docker:**

```json
{
  "mcpServers": {
    "solr-mcp": {
      "command": "docker",
      "args": ["run", "-i", "--rm",
        "-e", "SOLR_URL=http://host.docker.internal:8983/solr/",
        "ghcr.io/codeyogico/solr-mcp:latest"]
    }
  }
}
```

### Claude Code (CLI)

```bash
# STDIO via Homebrew
claude mcp add --transport stdio -e SOLR_URL=http://localhost:8983/solr/ solr-mcp -- solr-mcp

# or HTTP (this server speaks the SSE transport): start it, then add it
PROFILES=http solr-mcp &
claude mcp add --transport sse solr-mcp http://localhost:8080/sse
```

### MCP Inspector (HTTP mode)

```bash
PROFILES=http solr-mcp                       # or the docker/jar HTTP command above
npx @modelcontextprotocol/inspector          # connect to http://localhost:8080/sse
```

### Try it

Once connected, ask the assistant things like:

- *"List the Solr collections."* → `list-collections`
- *"Create a collection called `films`."* → `create-collection`
- *"Index this into films: `[{"id":"1","name":"The Matrix","genre_s":"scifi"}]`"* → `index-json-documents`
- *"Search films for name:Matrix."* → `search`
- *"What's the schema of films?"* → `get-schema`

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
