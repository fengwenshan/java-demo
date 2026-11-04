export const UPDATE_CHECK_HOURS = [10, 16] as const;
export const STARTUP_CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000;

/** 返回严格晚于 now 的下一个 10:00 或 16:00（本机时区）。 */
export function nextUpdateCheck(now = new Date()): Date {
  for (const hour of UPDATE_CHECK_HOURS) {
    const candidate = new Date(now);
    candidate.setHours(hour, 0, 0, 0);
    if (candidate.getTime() > now.getTime()) return candidate;
  }
  const tomorrow = new Date(now);
  tomorrow.setDate(tomorrow.getDate() + 1);
  tomorrow.setHours(UPDATE_CHECK_HOURS[0], 0, 0, 0);
  return tomorrow;
}

/** 启动时是否需要补检：从未检测，或距上次检测超过 6 小时。 */
export function shouldCheckOnStartup(
  nowMs: number,
  lastCheckMs: number | null,
): boolean {
  return lastCheckMs === null || nowMs - lastCheckMs >= STARTUP_CHECK_INTERVAL_MS;
}
