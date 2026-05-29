class SolrMcp < Formula
  desc "MCP server for Apache Solr (http4k + MCP Java SDK + SolrJ)"
  homepage "https://github.com/CodeYogiCo/solr-mcp"
  version "0.1.0"
  url "https://github.com/CodeYogiCo/solr-mcp/releases/download/v#{version}/solr-mcp-#{version}-all.jar"
  sha256 "REPLACE_WITH_RELEASE_JAR_SHA256"
  license "Apache-2.0"

  depends_on "openjdk@21"

  def install
    libexec.install "solr-mcp-#{version}-all.jar"
    # Generates a launcher at bin/solr-mcp that runs the jar with the right JRE.
    bin.write_jar_script libexec/"solr-mcp-#{version}-all.jar", "solr-mcp", java_version: "21"
  end

  def caveats
    <<~EOS
      solr-mcp speaks the Model Context Protocol.

      STDIO (default, for Claude Desktop):
        solr-mcp                      # reads JSON-RPC on stdin, writes to stdout

      HTTP mode (for MCP Inspector / remote):
        PROFILES=http solr-mcp        # listens on :8080 (SSE GET /sse, POST /message)

      Point it at your Solr with SOLR_URL (default http://localhost:8983/solr/).
    EOS
  end

  test do
    assert_path_exists libexec/"solr-mcp-#{version}-all.jar"
    assert_predicate bin/"solr-mcp", :executable?
  end
end
