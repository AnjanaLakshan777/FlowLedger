package com.flowledger.cashmanagement.dto;

import java.util.List;

public record SalesImportDetailResponse(SalesImportBatchResponse batch, List<SalesImportRowResponse> rows) {
}
