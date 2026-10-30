/** Monaco 编辑器语言选项与按扩展名自动识别（task-28）。 */

export interface EditorLanguageOption {
  value: string;
  label: string;
}

/** 手动切换下拉里的常用语言（中文标签）。 */
export const EDITOR_LANGUAGES: EditorLanguageOption[] = [
  { value: "plaintext", label: "纯文本" },
  { value: "json", label: "JSON" },
  { value: "javascript", label: "JavaScript" },
  { value: "typescript", label: "TypeScript" },
  { value: "html", label: "HTML" },
  { value: "css", label: "CSS" },
  { value: "scss", label: "SCSS" },
  { value: "less", label: "Less" },
  { value: "markdown", label: "Markdown" },
  { value: "java", label: "Java" },
  { value: "python", label: "Python" },
  { value: "xml", label: "XML" },
  { value: "yaml", label: "YAML" },
  { value: "sql", label: "SQL" },
  { value: "shell", label: "Shell" },
  { value: "go", label: "Go" },
  { value: "rust", label: "Rust" },
  { value: "php", label: "PHP" },
  { value: "ruby", label: "Ruby" },
  { value: "kotlin", label: "Kotlin" },
  { value: "swift", label: "Swift" },
  { value: "c", label: "C" },
  { value: "cpp", label: "C++" },
  { value: "csharp", label: "C#" },
  { value: "vue", label: "Vue" },
  { value: "dockerfile", label: "Dockerfile" },
  { value: "diff", label: "Diff" },
  { value: "graphql", label: "GraphQL" },
  { value: "ini", label: "INI" },
  { value: "toml", label: "TOML" },
];

const EXTENSION_LANGUAGE: Record<string, string> = {
  txt: "plaintext",
  text: "plaintext",
  log: "plaintext",
  out: "plaintext",
  md: "markdown",
  markdown: "markdown",
  json: "json",
  jsonc: "json",
  js: "javascript",
  mjs: "javascript",
  cjs: "javascript",
  jsx: "javascript",
  ts: "typescript",
  mts: "typescript",
  cts: "typescript",
  tsx: "typescript",
  html: "html",
  htm: "html",
  css: "css",
  scss: "scss",
  less: "less",
  java: "java",
  py: "python",
  xml: "xml",
  yaml: "yaml",
  yml: "yaml",
  sql: "sql",
  sh: "shell",
  bash: "shell",
  zsh: "shell",
  shell: "shell",
  go: "go",
  rs: "rust",
  php: "php",
  rb: "ruby",
  kt: "kotlin",
  swift: "swift",
  c: "c",
  h: "c",
  cpp: "cpp",
  cc: "cpp",
  cxx: "cpp",
  hpp: "cpp",
  cs: "csharp",
  vue: "vue",
  dockerfile: "dockerfile",
  diff: "diff",
  patch: "diff",
  graphql: "graphql",
  gql: "graphql",
  ini: "ini",
  cfg: "ini",
  conf: "ini",
  toml: "toml",
};

/** 按文件名/扩展名返回 Monaco language id；未知或没有扩展名回退 plaintext。 */
export function detectEditorLanguage(fileName: string): string {
  const name = fileName.trim().toLowerCase();
  if (!name) return "plaintext";
  const dot = name.lastIndexOf(".");
  const extension = dot >= 0 ? name.slice(dot + 1) : "";
  if (extension) return EXTENSION_LANGUAGE[extension] ?? "plaintext";
  // Dockerfile 等无扩展名的约定文件名。
  if (name === "dockerfile" || name.startsWith("dockerfile.")) return "dockerfile";
  if (name === "makefile") return "plaintext";
  return "plaintext";
}

/** 语言 id → 中文标签（用于 UI 提示/兜底）。 */
export function editorLanguageLabel(value: string): string {
  return EDITOR_LANGUAGES.find((option) => option.value === value)?.label ?? value;
}
