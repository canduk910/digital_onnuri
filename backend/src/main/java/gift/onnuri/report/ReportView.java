package gift.onnuri.report;

/** 제보 목록 행 — 컴포넌트명은 ReportContractTest가 고정. */
public record ReportView(long id, String title, String content, String page,
                         String nickname, String status, String createdAt) {
}
