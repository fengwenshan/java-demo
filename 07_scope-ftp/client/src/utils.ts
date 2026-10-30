/** 通用格式化工具。 */

/** 字节数 → 人类可读大小。 */
export function formatBytes(bytes: number | null | undefined): string {
  if (bytes === null || bytes === undefined || Number.isNaN(bytes)) return "-";
  if (bytes <= 0) return "0 B";
  const units = ["B", "KB", "MB", "GB", "TB"];
  const exponent = Math.min(
    Math.floor(Math.log(bytes) / Math.log(1024)),
    units.length - 1,
  );
  const value = bytes / 1024 ** exponent;
  const digits = exponent === 0 ? 0 : value >= 100 ? 0 : value >= 10 ? 1 : 2;
  return `${value.toFixed(digits)} ${units[exponent]}`;
}

/** ISO-8601 → 本地时间 `YYYY-MM-DD HH:mm:ss`。 */
export function formatDateTime(value?: string | null): string {
  if (!value) return "-";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  const pad = (input: number) => String(input).padStart(2, "0");
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(
    date.getDate(),
  )} ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(
    date.getSeconds(),
  )}`;
}

/** ISO-8601 → Finder 风格本地日期 `2026年10月29日 14:26`。 */
export function formatFinderDate(value?: string | null): string {
  if (!value) return "--";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "--";
  const pad = (input: number) => String(input).padStart(2, "0");
  return `${date.getFullYear()}年${date.getMonth() + 1}月${date.getDate()}日 ${pad(
    date.getHours(),
  )}:${pad(date.getMinutes())}`;
}

/** Finder「种类」列中文映射（task-26）。 */
const FILE_KIND_BY_EXTENSION: Record<string, string> = {
  pdf: "PDF 文稿",
  ppt: "PPT 演示文稿",
  pptx: "PPT 演示文稿",
  doc: "Word 文稿",
  docx: "Word 文稿",
  xls: "Excel 表格",
  xlsx: "Excel 表格",
  csv: "Excel 表格",
  txt: "文本文稿",
  md: "文本文稿",
  log: "文本文稿",
  json: "文本文稿",
  xml: "文本文稿",
  yml: "文本文稿",
  yaml: "文本文稿",
  js: "文本文稿",
  ts: "文本文稿",
  tsx: "文本文稿",
  jsx: "文本文稿",
  vue: "文本文稿",
  html: "文本文稿",
  css: "文本文稿",
  sh: "文本文稿",
  png: "图片",
  jpg: "图片",
  jpeg: "图片",
  gif: "图片",
  webp: "图片",
  bmp: "图片",
  svg: "图片",
  zip: "压缩包",
  rar: "压缩包",
  "7z": "压缩包",
  tar: "压缩包",
  gz: "压缩包",
  bz2: "压缩包",
  mp3: "音频",
  wav: "音频",
  flac: "音频",
  aac: "音频",
  m4a: "音频",
  ogg: "音频",
  mp4: "视频",
  mov: "视频",
  avi: "视频",
  mkv: "视频",
  webm: "视频",
};

/** 按条目类型/扩展名返回 Finder 种类文案。 */
export function fileKindLabel(name: string, type: string): string {
  if (type === "directory") return "文件夹";
  return FILE_KIND_BY_EXTENSION[fileExtension(name)] ?? "文件";
}

/** 从路径中取文件名。 */
export function fileNameFromPath(path: string): string {
  const segments = path.split(/[\\/]/);
  return segments[segments.length - 1] || path;
}

/** 取扩展名（小写，不含点）。 */
export function fileExtension(name: string): string {
  const index = name.lastIndexOf(".");
  if (index <= 0 || index === name.length - 1) return "";
  return name.slice(index + 1).toLowerCase();
}

/** 生成 UUID（优先 crypto.randomUUID）。 */
export function createId(): string {
  if (typeof crypto !== "undefined" && "randomUUID" in crypto) {
    return crypto.randomUUID();
  }
  return `${Date.now().toString(16)}-${Math.random().toString(16).slice(2)}`;
}

/** 截断 SHA-256 展示。 */
export function shortSha256(value: string, length = 16): string {
  if (!value) return "-";
  return value.length > length ? `${value.slice(0, length)}…` : value;
}
