/*
 * Copyright (c) 2017 Bosch Software Innovations GmbH.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/org/documents/epl-2.0/index.php
 *
 * Contributors:
 *    Bosch Software Innovations GmbH - initial contribution
 */
package org.eclipse.ditto.model.thingsearchparser.predicates.ast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.Test;

import nl.jqno.equalsverifier.EqualsVerifier;
import nl.jqno.equalsverifier.Warning;

/**
 * Tests {@link MultiComparisonNode}.
 */
public class MultiComparisonNodeTest {

    /** */
    @Test
    public void hashcodeAndEquals() {
        EqualsVerifier.forClass(MultiComparisonNode.class) //
                .usingGetClass() //
                .suppress(Warning.REFERENCE_EQUALITY) //
                .verify();
    }

    /** */
    @Test(expected = NullPointerException.class)
    public void typeConstructorWithNullAsFilterProperty() {
        new MultiComparisonNode(org.eclipse.ditto.model.thingsearchparser.predicates.ast.MultiComparisonNode.Type.in,
                null);
    }

    /** */
    @Test
    public void typeConstructorSuccess() {
        final MultiComparisonNode filterNode = new MultiComparisonNode(
                org.eclipse.ditto.model.thingsearchparser.predicates.ast.MultiComparisonNode.Type.in, "propertyName");
        assertThat(filterNode.getComparisonType()).isEqualTo(
                org.eclipse.ditto.model.thingsearchparser.predicates.ast.MultiComparisonNode.Type.in);
        assertThat(filterNode.getComparisonProperty()).isEqualTo("propertyName");
        assertThat(filterNode.getComparisonValue()).isEmpty();

        filterNode.addValue("test");
        assertThat(filterNode.getComparisonValue().get(0)).isEqualTo("test");
    }

    /** */
    @Test
    public void addNullValues() {
        final MultiComparisonNode filterNode = new MultiComparisonNode(
                org.eclipse.ditto.model.thingsearchparser.predicates.ast.MultiComparisonNode.Type.in, "propertyName");

        filterNode.addValue("test1");
        filterNode.addValue(null);
        filterNode.addValue("test2");
        filterNode.addValue(null);
        filterNode.addValue(null);
        filterNode.addValue("test3");
        assertThat(filterNode.getComparisonValue().get(0)).isEqualTo("test1");
        assertThat(filterNode.getComparisonValue().get(1)).isNull();
        assertThat(filterNode.getComparisonValue().get(2)).isEqualTo("test2");
        assertThat(filterNode.getComparisonValue().get(3)).isNull();
        assertThat(filterNode.getComparisonValue().get(4)).isNull();
        assertThat(filterNode.getComparisonValue().get(5)).isEqualTo("test3");
    }

    @Test
    public void visitorGetsVisited() {
        final PredicateVisitor visitorMock = mock(PredicateVisitor.class);
        final MultiComparisonNode MultiComparisonNode = new MultiComparisonNode(
                org.eclipse.ditto.model.thingsearchparser.predicates.ast.MultiComparisonNode.Type.in, "propertyName");
        MultiComparisonNode.accept(visitorMock);
        verify(visitorMock).visit(MultiComparisonNode);
    }

}
