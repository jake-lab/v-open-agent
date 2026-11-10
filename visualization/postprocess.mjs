import { existsSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const visualizationDir = dirname(fileURLToPath(import.meta.url));
const diagramFiles = [
  "architecture.html",
  "stage-a-engine.html",
  "stage-b-simulator.html",
  "stage-c-android-vision.html",
];

for (const file of diagramFiles) {
  const path = join(visualizationDir, file);
  if (!existsSync(path)) {
    throw new Error(`missing diagram: ${file}`);
  }
  const sanitized = readFileSync(path, "utf8")
    .replaceAll("\u00b7", "/")
    .replace(/[ \t]+$/gm, "");
  writeFileSync(path, sanitized);
}

const architecturePath = join(visualizationDir, "architecture.html");
let architecture = readFileSync(architecturePath, "utf8");

const styleMarker = "/* v-open-agent:stage-links:styles */";
if (!architecture.includes(styleMarker)) {
  const styleEnd = architecture.lastIndexOf("</style>");
  if (styleEnd < 0) {
    throw new Error("architecture style anchor not found");
  }
  const styles = `
    ${styleMarker}
    .stage-links {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 0.5rem;
      margin: -0.25rem 0 1rem 1.375rem;
    }
    .stage-links-label {
      color: var(--text-muted);
      font-size: 0.75rem;
      font-weight: 650;
      letter-spacing: 0.02em;
    }
    .stage-link {
      display: inline-flex;
      align-items: baseline;
      gap: 0.35rem;
      min-height: 2rem;
      padding: 0.35rem 0.7rem;
      border: 1px solid var(--panel-border);
      border-radius: 999px;
      background: var(--panel);
      color: var(--text);
      font-size: 0.78rem;
      font-weight: 650;
      text-decoration: none;
      transition: border-color 0.15s, color 0.15s, background 0.15s;
    }
    .stage-link small {
      color: var(--text-muted);
      font-size: 0.72rem;
      font-weight: 500;
    }
    .stage-link:hover,
    .stage-link:focus-visible {
      border-color: var(--frontend-stroke);
      color: var(--frontend-stroke);
      outline: none;
    }
    html[data-present="true"] .stage-links { display: none; }
    @media (max-width: 720px) {
      .stage-links { margin-left: 0; }
      .stage-links-label { flex-basis: 100%; }
    }
    @media print {
      .stage-links { display: none !important; }
    }
`;
  architecture = `${architecture.slice(0, styleEnd)}${styles}${architecture.slice(styleEnd)}`;
}

const navMarker = "<!-- v-open-agent:stage-links -->";
if (!architecture.includes(navMarker)) {
  const scriptAnchor = '    <script id="archify-source-evidence-data"';
  const scriptIndex = architecture.indexOf(scriptAnchor);
  if (scriptIndex < 0) {
    throw new Error("architecture header anchor not found");
  }
  const navigation = `    ${navMarker}
    <nav class="stage-links no-print" aria-label="Stage 상세 구성도">
      <span class="stage-links-label">Stage 상세</span>
      <a class="stage-link" href="stage-a-engine.html">Stage A <small>엔진</small></a>
      <a class="stage-link" href="stage-b-simulator.html">Stage B <small>Simulator</small></a>
      <a class="stage-link" href="stage-c-android-vision.html">Stage C <small>Android Vision</small></a>
    </nav>

`;
  architecture = `${architecture.slice(0, scriptIndex)}${navigation}${architecture.slice(scriptIndex)}`;
}

writeFileSync(architecturePath, architecture);
