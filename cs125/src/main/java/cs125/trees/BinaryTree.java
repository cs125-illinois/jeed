package cs125.trees;

import java.util.Objects;
import java.util.Random;

@SuppressWarnings("unused")
public class BinaryTree<T> {
    private final T value;
    private BinaryTree<T> right;
    private BinaryTree<T> left;
    private int size = 1;

    public BinaryTree(T setValue) {
        value = setValue;
    }

    public BinaryTree(T setValue, T setLeft, T setRight) {
        value = setValue;
        left = new BinaryTree(setLeft);
        right = new BinaryTree(setRight);
    }

    public void setRight(BinaryTree<T> setRight) {
        right = setRight;
    }

    public void setLeft(BinaryTree<T> setLeft) {
        left = setLeft;
    }

    public BinaryTree<T> getLeft() {
        return left;
    }

    public BinaryTree<T> getRight() {
        return right;
    }

    public T getValue() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof BinaryTree)) {
            return false;
        }
        @SuppressWarnings("rawtypes") BinaryTree other = (BinaryTree) o;
        if (!(value.equals(other.value))) {
            return false;
        }
        return Objects.equals(right, other.right) && Objects.equals(left, other.left);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value, right, left);
    }

    @Override
    public String toString() {
        return "BinaryTree(" + size + " nodes)";
    }

    private static <T> void add(BinaryTree<T> tree, T value, Random random) {
        if (random.nextBoolean()) {
            if (tree.getRight() == null) {
                tree.setRight(new BinaryTree<>(value));
            } else {
                add(tree.getRight(), value, random);
            }
        } else {
            if (tree.getLeft() == null) {
                tree.setLeft(new BinaryTree<>(value));
            } else {
                add(tree.getLeft(), value, random);
            }
        }
        tree.size += 1;
    }

    public static BinaryTree<Integer> randomIntegerTree(Random random, int size, int maxInteger) {
        if (size == 0) {
            throw new IllegalArgumentException("size must be positive: " + size);
        }
        BinaryTree<Integer> tree = new BinaryTree<>(random.nextInt(maxInteger / 2) - maxInteger);
        for (int i = 1; i < size; i++) {
            add(tree, random.nextInt(maxInteger / 2) - maxInteger, random);
        }
        return tree;
    }

    public static BinaryTree<Integer> randomIntegerTree(int size, int maxInteger) {
        Random random = new Random();
        random.setSeed(0);
        return randomIntegerTree(random, size, maxInteger);
    }

    public static BinaryTree<Integer> randomIntegerTree(int size) {
        return randomIntegerTree(size, 128);
    }
}

