package graphql.validation.rules;


import graphql.Directives;
import graphql.language.*;
import graphql.validation.AbstractRule;
import graphql.validation.ValidationContext;
import graphql.validation.ValidationErrorCollector;
import graphql.validation.ValidationErrorType;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class NoUndefinedVariables extends AbstractRule {

    private final Set<String> variableNames = new LinkedHashSet<>();

    public NoUndefinedVariables(ValidationContext validationContext, ValidationErrorCollector validationErrorCollector) {
        super(validationContext, validationErrorCollector);
        setVisitFragmentSpreads(true);
    }

    @Override
    public void checkOperationDefinition(OperationDefinition operationDefinition) {
        variableNames.clear();
    }

    @Override
    public void checkFragmentDefinition(FragmentDefinition fragmentDefinition) {
        super.checkFragmentDefinition(fragmentDefinition);
    }

    @Override
    public void checkVariable(VariableReference variableReference) {
        if (!variableNames.contains(variableReference.getName())) {
            String message = String.format("Undefined variable %s", variableReference.getName());
            addError(ValidationErrorType.UndefinedVariable, variableReference.getSourceLocation(), message);
        }
    }

    @Override
    public void checkVariableDefinition(VariableDefinition variableDefinition) {
        variableNames.add(variableDefinition.getName());
    }

    @Override
    public void checkDirective(Directive directive, List<Node> ancestors) {
        if (directive.getName().equals(Directives.ExportDirective.getName())) {
            StringValue variableName = (StringValue) directive.getArgument("as").getValue();
            variableNames.add(variableName.getValue());
        }
    }
}
