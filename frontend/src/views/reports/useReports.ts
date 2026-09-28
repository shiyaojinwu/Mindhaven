import { ref, watch } from "vue";
import {
  listReports,
  getAnalysis,
  generateAnalysis,
} from "../../api/reports.js";
import type { Report } from "../../types/reports.js";
import type { ActionRunner } from "../../types/common.js";
import type { ReportAnalysis } from "../../types/reports.js";
export function useReports(run: ActionRunner) {
  const reports = ref<Report[]>([]);
  const activeReport = ref<Report | null>(null);
  const reportAnalysis = ref<ReportAnalysis | null>(null);
  let requestVersion = 0;
  watch(activeReport, async (report, _, onCleanup) => {
    const version = ++requestVersion;
    let current = true;
    onCleanup(() => {
      current = false;
    });
    reportAnalysis.value = null;
    if (!report) return;
    try {
      const data = await getAnalysis(report.id);
      if (current && version === requestVersion)
        reportAnalysis.value = data.content ? data : null;
    } catch {
      /* Existing report remains readable when cached analysis is unavailable. */
    }
  });
  async function loadReports() {
    reports.value = await listReports();
  }
  async function analyzeReport() {
    const report = activeReport.value;
    if (!report) return;
    const version = ++requestVersion;
    await run(async () => {
      const data = await generateAnalysis(report.id);
      if (activeReport.value === report && version === requestVersion)
        reportAnalysis.value = data;
    });
  }
  function acceptReport(report: Report) {
    reports.value.unshift(report);
    activeReport.value = report;
  }
  return {
    reports,
    activeReport,
    reportAnalysis,
    loadReports,
    analyzeReport,
    acceptReport,
  };
}
