package com.eks.irsa.auditor;

import java.util.List;

public class IRSAReport {
    private String serviceAccount;
    private String role;
    private String namespace;

    public String getNamespace() {
        return this.namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }
    private List<Policy> policies;

    public String getServiceAccount() {
        return this.serviceAccount;
    }

    public void setServiceAccount(String serviceAccount) {
        this.serviceAccount = serviceAccount;
    }

    public String getRole() {
        return this.role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public List<Policy> getPolicies() {
        return this.policies;
    }

    public void setPolicies(List<Policy> policies) {
        this.policies = policies;
    }


    @Override
    public String toString() {
        return "{" +
            " serviceAccount='" + getServiceAccount() + "'" +
            ", role='" + getRole() + "'" +
            ", policies='" + getPolicies().toString() + "'" +
            "}\n";
    }

}
