package cs125.sorting.quicksort;

@SuppressWarnings("unused")
public class Partitioner {
    public static <T extends Comparable<T>> int partition(T[] values) {
        if (values == null || values.length == 0) {
            throw new IllegalArgumentException();
        }

        T tmp;
        int pivotPosition = 0;
        for (int i = 1; i < values.length; i++) {
            if (values[i].compareTo(values[0]) < 0) {
                pivotPosition++;
                tmp = values[pivotPosition];
                values[pivotPosition] = values[i];
                values[i] = tmp;
            }
        }

        tmp = values[pivotPosition];
        values[pivotPosition] = values[0];
        values[0] = tmp;
        return pivotPosition;
    }
}
