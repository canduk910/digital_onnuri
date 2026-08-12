package gift.onnuri.report;

/** 제보 등록 본문 — 컴포넌트명은 ReportContractTest가 고정(report.html과 한 변경 단위). */
public record ReportCreate(String title, String content, String page, String nickname) {
}
