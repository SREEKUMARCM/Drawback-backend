package com.drawback.review.web;

import com.drawback.review.service.ReconciliationService;
import com.drawback.review.web.Dtos.ReconcileReport;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    private final ReconciliationService reconciliation;

    public AdminController(ReconciliationService reconciliation) {
        this.reconciliation = reconciliation;
    }

    @PostMapping("/reconcile")
    @PreAuthorize("hasRole('ADMIN')")
    public ReconcileReport reconcile() {
        return reconciliation.reconcileAll();
    }
}
