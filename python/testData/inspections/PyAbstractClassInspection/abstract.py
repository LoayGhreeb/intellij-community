import abc


class A(metaclass=abc.ABCMeta):
    @abc.abstractproperty
    def foo(self):
        pass


class <weak_warning descr="Class C must implement all abstract methods">C</weak_warning>(A):
    def bar(self):
        pass
