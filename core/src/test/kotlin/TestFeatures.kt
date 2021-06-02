package edu.illinois.cs.cs125.jeed.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class TestFeatures : StringSpec({
    "should count variable declarations in snippets" {
        Source.fromSnippet(
            """
int i = 0;
int j;
i = 4;
i++;
""".trim()
        ).features().also {
            it.lookup(".").features.featureMap[FeatureName.LOCAL_VARIABLE_DECLARATIONS] shouldBe 2
            it.lookup(".").features.featureMap[FeatureName.VARIABLE_ASSIGNMENTS] shouldBe 2
            it.lookup(".").features.featureMap[FeatureName.VARIABLE_REASSIGNMENTS] shouldBe 1
            it.lookup("").features.featureMap[FeatureName.METHOD] shouldBe 1
        }
    }
    "should count for loops in snippets" {
        Source.fromSnippet(
            """
for (int i = 0; i < 10; i++) {
    System.out.println(i);
}
int[] arr = new int[10];
for (int num : arr) {
    num++;
}
""".trim()
        ).features().also {
            it.lookup(".").features.featureMap[FeatureName.FOR_LOOPS] shouldBe 2
            it.lookup(".").features.featureMap[FeatureName.VARIABLE_ASSIGNMENTS] shouldBe 2
            it.lookup(".").features.featureMap[FeatureName.LOCAL_VARIABLE_DECLARATIONS] shouldBe 3
            it.lookup(".").features.featureMap[FeatureName.ENHANCED_FOR] shouldBe 1
        }
    }
    "should count nested for loops in snippets" {
        Source.fromSnippet(
            """
for (int i = 0; i < 10; i++) {
    for (int j = 0; j < 10; j++) {
        System.out.println(i + j);
    }
}
""".trim()
        ).features().also {
            it.lookup(".").features.featureMap[FeatureName.FOR_LOOPS] shouldBe 2
            it.lookup(".").features.featureMap[FeatureName.NESTED_FOR] shouldBe 1
        }
    }
    "should count while loops in snippets" {
        Source.fromSnippet(
            """
int i = 0;
while (i < 10) {
    while (j < 10) {
        j++;
    }
    i++;
}
""".trim()
        ).features().also {
            it.lookup(".").features.featureMap[FeatureName.WHILE_LOOPS] shouldBe 2
            it.lookup(".").features.featureMap[FeatureName.NESTED_WHILE] shouldBe 1
            it.lookup(".").features.featureMap[FeatureName.DO_WHILE_LOOPS] shouldBe 0
        }
    }
    "should count do-while loops in snippets" {
        Source.fromSnippet(
            """
int i = 0;
do {
    System.out.println(i);
    i++;
    
    int j = 0;
    do {
        j++;
    } while (j < 10);
} while (i < 10);
""".trim()
        ).features().also {
            it.lookup(".").features.featureMap[FeatureName.DO_WHILE_LOOPS] shouldBe 2
            it.lookup(".").features.featureMap[FeatureName.NESTED_DO_WHILE] shouldBe 1
            it.lookup(".").features.featureMap[FeatureName.WHILE_LOOPS] shouldBe 0
        }
    }
    "should count simple if-else statements in snippets" {
        Source.fromSnippet(
            """
int i = 0;
if (i < 5) {
    i++;
} else {
    i--;
}
""".trim()
        ).features().also {
            it.lookup(".").features.featureMap[FeatureName.IF_STATEMENTS] shouldBe 1
            it.lookup(".").features.featureMap[FeatureName.ELSE_STATEMENTS] shouldBe 1
        }
    }
    "should count a chain of if-else statements in snippets" {
        Source.fromSnippet(
            """
int i = 0;
if (i < 5) {
    i++;
} else if (i < 10) {
    i--;
} else if (i < 15) {
    i++;
} else {
    i--;
}
""".trim()
        ).features().also {
            it.lookup(".").features.featureMap[FeatureName.IF_STATEMENTS] shouldBe 1
            it.lookup(".").features.featureMap[FeatureName.ELSE_STATEMENTS] shouldBe 1
            it.lookup(".").features.featureMap[FeatureName.ELSE_IF] shouldBe 2
        }
    }
    "should count nested if statements in snippets" {
        Source.fromSnippet(
            """
int i = 0;
if (i < 15) {
    if (i < 10) {
        i--;
        if (i < 5) {
            i++;
        }
    }
    if (i > 10) {
        i--;
    }
}
""".trim()
        ).features().also {
            it.lookup(".").features.featureMap[FeatureName.IF_STATEMENTS] shouldBe 4
            it.lookup(".").features.featureMap[FeatureName.NESTED_IF] shouldBe 3
        }
    }
    "should count conditional expressions and complex conditionals in snippets" {
        Source.fromSnippet(
            """
int i = 0;
if (i < 5 || i > 15) {
    if (i < 0) {
        i--;
    }
} else if (i > 5 && i < 15) {
    i++;
} else {
    i--;
}
""".trim()
        ).features().also {
            it.lookup(".").features.featureMap[FeatureName.CONDITIONAL] shouldBe 5
            it.lookup(".").features.featureMap[FeatureName.COMPLEX_CONDITIONAL] shouldBe 2
        }
    }
    "should count try blocks, switch statements, and assertions in snippets" {
        Source.fromSnippet(
            """
int i = 0;
try {
    assert i > -1;
    switch(i) {
        case 0:
            System.out.println("zero");
            break;
        case 1:
            System.out.println("one");
            break;
        default:
            System.out.println("not zero or one");
    }
} catch (Exception e) { }
        
""".trim()
        ).features().also {
            it.lookup(".").features.featureMap[FeatureName.TRY_BLOCK] shouldBe 1
            it.lookup(".").features.featureMap[FeatureName.ASSERT] shouldBe 1
            it.lookup(".").features.featureMap[FeatureName.SWITCH] shouldBe 1
        }
    }
    "should count operators in snippets" {
        Source.fromSnippet(
            """
int i = 0;
int j = 0;
if (i < 5) {
    i += 5;
    j = i - 1;
} else if (i < 10) {
    i++;
    j = j & i;
} else if (i < 15) {
    i--;
    j = j % i;
} else {
    i -= 5;
    j = i < j ? i : j;
}
j = j << 2;
""".trim()
        ).features().also {
            it.lookup(".").features.featureMap[FeatureName.UNARY_OPERATORS] shouldBe 2
            it.lookup(".").features.featureMap[FeatureName.ARITHMETIC_OPERATORS] shouldBe 2
            it.lookup(".").features.featureMap[FeatureName.BITWISE_OPERATORS] shouldBe 2
            it.lookup(".").features.featureMap[FeatureName.ASSIGNMENT_OPERATORS] shouldBe 2
            it.lookup(".").features.featureMap[FeatureName.TERNARY_OPERATOR] shouldBe 1
        }
    }
    "should count the new keyword and array accesses in snippets" {
        Source.fromSnippet(
            """
int[] arr = new int[3];
arr[0 + 0] = 5;
arr[1] = 10;
arr[2] = arr[0] + arr[1];
int[] nums = {1, 2, 4};
""".trim()
        ).features().also {
            it.lookup(".").features.featureMap[FeatureName.NEW_KEYWORD] shouldBe 1
            it.lookup(".").features.featureMap[FeatureName.ARRAY_ACCESS] shouldBe 5
            it.lookup(".").features.featureMap[FeatureName.ARRAY_LITERAL] shouldBe 1
        }
    }
    "should count strings and null in snippets" {
        Source.fromSnippet(
            """
String first = "Hello, world!";
String second = null;
""".trim()
        ).features().also {
            it.lookup(".").features.featureMap[FeatureName.STRING] shouldBe 2
            it.lookup(".").features.featureMap[FeatureName.NULL] shouldBe 1
        }
    }
    "should count multidimensional arrays in snippets" {
        Source.fromSnippet(
            """
int[][] array = new int[5][5];
char[][] array1 = new char[10][10];
""".trim()
        ).features().also {
            it.lookup(".").features.featureMap[FeatureName.MULTIDIMENSIONAL_ARRAYS] shouldBe 2
        }
    }
})
