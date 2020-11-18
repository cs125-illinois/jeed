package cs125.sorting.quicksort;

@SuppressWarnings("unused")
public class Partitioner {
    public static <T extends Comparable<T>> int partition(T[] values, int start, int end) {
        if (values == null || values.length == 0) {
            throw new IllegalArgumentException();
        }

        T tmp;
        int pivotPosition = start;
        for (int i = start + 1; i < end; i++) {
            if (values[i].compareTo(values[start]) < 0) {
                pivotPosition++;
                tmp = values[pivotPosition];
                values[pivotPosition] = values[i];
                values[i] = tmp;
            }
        }

        tmp = values[pivotPosition];
        values[pivotPosition] = values[start];
        values[start] = tmp;
        return pivotPosition;
    }

    public static <T extends Comparable<T>> int partition(T[] values) {
        return partition(values, 0, values.length);
    }
}
