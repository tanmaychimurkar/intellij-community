// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.siyeh.ipp.switchbranches;

import com.intellij.codeInsight.daemon.impl.analysis.HighlightingFeature;
import com.intellij.codeInspection.PsiUpdateModCommandAction;
import com.intellij.codeInspection.dataFlow.CommonDataflow;
import com.intellij.codeInspection.dataFlow.rangeSet.LongRangeSet;
import com.intellij.codeInspection.dataFlow.types.DfIntType;
import com.intellij.codeInspection.magicConstant.MagicConstantUtils;
import com.intellij.modcommand.ModPsiUpdater;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtil;
import com.intellij.psi.util.TypeConversionUtil;
import com.intellij.util.ObjectUtils;
import com.intellij.util.containers.ContainerUtil;
import com.siyeh.IntentionPowerPackBundle;
import com.siyeh.ig.psiutils.CreateSwitchBranchesUtil;
import com.siyeh.ig.psiutils.ExpressionUtils;
import com.siyeh.ig.psiutils.TypeUtils;
import one.util.streamex.StreamEx;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.stream.Collectors;

public class CreateMissingSwitchBranchesAction extends PsiUpdateModCommandAction<PsiSwitchBlock> {
  private static final int MAX_NUMBER_OF_BRANCHES = 100;
  private List<Value> myAllValues;

  public CreateMissingSwitchBranchesAction() {
    super(PsiSwitchBlock.class);
  }

  @Override
  protected void invoke(@NotNull ActionContext context, @NotNull PsiSwitchBlock block, @NotNull ModPsiUpdater updater) {
    if (block instanceof PsiSwitchExpression && !HighlightingFeature.SWITCH_EXPRESSION.isAvailable(block)) {
      // Do not suggest if switch expression is not supported as we may generate unparseable code with 'yield' statement
      return;
    }
    List<Value> allValues = myAllValues;
    List<Value> missingValues = getMissingValues(block, allValues);
    if (missingValues.isEmpty()) return;
    List<String> allValueNames = ContainerUtil.map(allValues, v -> v.myName);
    List<String> missingValueNames = ContainerUtil.map(missingValues, v -> v.myName);
    List<PsiSwitchLabelStatementBase> addedLabels =
      CreateSwitchBranchesUtil.createMissingBranches(block, allValueNames, missingValueNames,
                                                     label -> extractConstantNames(allValues, label));
    CreateSwitchBranchesUtil.createTemplate(block, addedLabels, updater);
  }

  private static List<String> extractConstantNames(List<Value> allValues, PsiSwitchLabelStatementBase label) {
    Set<Object> constants = getLabelConstants(label);
    return StreamEx.of(allValues).filter(v -> constants.contains(v.myValue)).map(v -> v.myName).toList();
  }

  @Override
  protected @Nullable Presentation getPresentation(@NotNull ActionContext context, @NotNull PsiSwitchBlock block) {
    if (block != PsiTreeUtil.getParentOfType(context.findLeaf(), PsiSwitchBlock.class, false, PsiCodeBlock.class, PsiStatement.class)) {
      return null;
    }
    PsiExpression expression = PsiUtil.skipParenthesizedExprDown(block.getExpression());
    if (expression == null) return null;
    PsiType type = expression.getType();
    if (type == null) return null;
    boolean isString = TypeUtils.isJavaLangString(type);
    if (type instanceof PsiClassType && !isString) {
      type = PsiPrimitiveType.getUnboxedType(type);
      if (type == null) return null;
    }

    List<Value> values = getPossibleValues(expression, type);
    if (values.isEmpty()) return null;
    List<Value> missingValues = getMissingValues(block, values);
    if (missingValues.isEmpty()) return null;
    myAllValues = values;
    return Presentation.of(CreateSwitchBranchesUtil.getActionName(ContainerUtil.map(missingValues, v -> v.myPresentationName)));
  }

  @NotNull
  private static List<Value> getPossibleValues(PsiExpression expression, PsiType type) {
    CommonDataflow.DataflowResult dfr = CommonDataflow.getDataflowResult(expression);
    if (dfr != null) {
      LongRangeSet range = DfIntType.extractRange(dfr.getDfType(expression));
      if (type != null && PsiTypes.intType().isAssignableFrom(type) && !range.isCardinalityBigger(MAX_NUMBER_OF_BRANCHES)) {
        return range.stream().mapToObj(c -> Value.fromConstant(TypeConversionUtil.computeCastTo(c, type))).collect(Collectors.toList());
      }
      Set<Object> values = dfr.getExpressionValues(expression);
      if (!values.isEmpty() && values.size() <= MAX_NUMBER_OF_BRANCHES && ContainerUtil.and(values, v -> v instanceof String)) {
        return values.stream().map(Value::fromConstant).sorted(Comparator.comparing(v -> (String)v.myValue)).collect(Collectors.toList());
      }
    }
    if (expression instanceof PsiReferenceExpression ref) {
      PsiModifierListOwner target = ObjectUtils.tryCast(ref.resolve(), PsiModifierListOwner.class);
      List<Value> values = getValues(target, type, expression);
      if (values != null) return values;
    }
    else if (expression instanceof PsiMethodCallExpression call) {
      PsiModifierListOwner target = call.resolveMethod();
      List<Value> values = getValues(target, type, expression);
      if (values != null) return values;
    }
    return Collections.emptyList();
  }

  private static List<Value> getValues(PsiModifierListOwner target, PsiType type, PsiElement context) {
    if (target == null) {
      return null;
    }
    MagicConstantUtils.AllowedValues values = MagicConstantUtils.getAllowedValues(target, type, context);
    if (values != null && !values.isFlagSet() && values.getValues().length <= MAX_NUMBER_OF_BRANCHES) {
      List<Value> result = new ArrayList<>();
      for (PsiAnnotationMemberValue value : values.getValues()) {
        Value val = null;
        if (value instanceof PsiReferenceExpression ref) {
          PsiField field = ObjectUtils.tryCast(ref.resolve(), PsiField.class);
          if (field != null) {
            val = Value.fromField(field);
          }
        }
        else if (value instanceof PsiExpression expr) {
          final Object o = ExpressionUtils.computeConstantExpression(expr);
          val = Value.fromConstant(o);
        }
        if (val == null) return Collections.emptyList();
        result.add(val);
      }
      return result;
    }
    return null;
  }

  @NotNull
  private static List<Value> getMissingValues(PsiSwitchBlock block, List<Value> allValues) {
    List<Value> missingValues = new ArrayList<>(allValues);
    PsiCodeBlock body = block.getBody();
    if (body != null) {
      Collection<PsiSwitchLabelStatementBase> labels = PsiTreeUtil.findChildrenOfType(body, PsiSwitchLabelStatementBase.class);
      Set<Object> existingBranches = StreamEx.of(labels)
        .toFlatCollection(CreateMissingSwitchBranchesAction::getLabelConstants, HashSet::new);
      missingValues.removeIf(v -> existingBranches.contains(v.myValue));
    }
    return missingValues;
  }

  @Nls(capitalization = Nls.Capitalization.Sentence)
  @NotNull
  @Override
  public String getFamilyName() {
    return IntentionPowerPackBundle.message("create.missing.switch.branches.family.name");
  }

  @NotNull
  private static Set<Object> getLabelConstants(@NotNull PsiSwitchLabelStatementBase label) {
    final PsiCaseLabelElementList list = label.getCaseLabelElementList();
    if (list == null) {
      return Collections.emptySet();
    }
    Set<Object> constants = new HashSet<>();
    for (PsiCaseLabelElement caseLabelElement : list.getElements()) {
      PsiExpression expression = ObjectUtils.tryCast(caseLabelElement, PsiExpression.class);
      Object constant = ExpressionUtils.computeConstantExpression(expression);
      if (constant instanceof Byte || constant instanceof Short) {
        constants.add(((Number)constant).intValue());
      }
      else if (constant instanceof String || constant instanceof Integer) {
        constants.add(constant);
      }
      else if (constant instanceof Character) {
        constants.add((int)(char)constant);
      } else {
        return Collections.emptySet();
      }
    }
    return constants;
  }

  private static class Value {
    final @NotNull String myName;
    final @NotNull String myPresentationName;
    final @NotNull Object myValue;

    Value(@NotNull String name, @NotNull String presentationName, @NotNull Object value) {
      myName = name;
      myPresentationName = presentationName;
      myValue = value;
    }

    @NotNull
    static Value fromConstant(Object value) {
      Object normalized = value;
      if (value instanceof Byte || value instanceof Short) {
        normalized = ((Number)value).intValue();
      }
      else if (value instanceof Character ch) {
        normalized = (int)ch;
      }
      if (normalized instanceof Integer || normalized instanceof String) {
        String presentation = getPresentation(value);
        if (presentation != null) {
          return new Value(presentation, presentation, normalized);
        }
      }
      throw new IllegalArgumentException("Unexpected constant supplied: " + value);
    }

    @Nullable
    static Value fromField(@NotNull PsiField field) {
      String name = field.getName();
      PsiClass aClass = field.getContainingClass();
      if (aClass == null) return null;
      String className = aClass.getQualifiedName();
      if (className == null) return null;
      Object value = field.computeConstantValue();
      if (value == null) return null;
      return new Value(className + "." + field.getName(), name, value);
    }

    @Nullable
    static String getPresentation(Object constant) {
      if (constant instanceof Integer || constant instanceof Byte || constant instanceof Short) {
        return constant.toString();
      }
      if (constant instanceof String str) {
        return '"' + StringUtil.escapeStringCharacters(str) + '"';
      }
      if (constant instanceof Character) {
        return "'" + StringUtil.escapeCharCharacters(constant.toString()) + "'";
      }
      return null;
    }
  }
}
