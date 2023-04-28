package com.eks.irsa.auditor;

import java.util.List;

public class Policy {
    private String name;
    private String arn;
    private List<Statement> statements;

    public String getName() {
        return this.name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getArn() {
        return this.arn;
    }

    public void setArn(String arn) {
        this.arn = arn;
    }

    public List<Statement> getStatements() {
        return this.statements;
    }

    public void setStatements(List<Statement> statements) {
        this.statements = statements;
    }


    @Override
    public String toString() {
        return "{" +
            " name='" + getName() + "'" +
            ", arn='" + getArn() + "'" +
            ", statements='" + getStatements().toString() + "'" +
            "}";
    }

}
