package p;

public class A {
    public interface Bar<T> { }

    public interface Base<T> {

		void m(Bar<T> bar); }

    public class B<T,U> implements Base<U> {
    	@Override
		public void m(Bar<U> bar) { }// pull method foo up to interface Base
    }
}