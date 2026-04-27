package cn.tesseract.crosshook;

public final class Callback<T> {
    public final Object[] args;
    public T thisObject;
    private Object returnValue;
    private boolean proceed = true;

    public Callback(T thisObject, Object[] args) {
        this.thisObject = thisObject;
        this.args = args == null ? new Object[0] : args;
    }

    public T getThisObject() {
        return thisObject;
    }

    public void setThisObject(T thisObject) {
        this.thisObject = thisObject;
    }

    public Object[] getArgs() {
        return args;
    }

    public Object getArg(int index) {
        return args[index];
    }

    public void setArg(int index, Object value) {
        args[index] = value;
    }

    public Object getReturnValue() {
        return returnValue;
    }

    public void setReturnValue(Object returnValue) {
        this.returnValue = returnValue;
        this.proceed = false;
    }

    public boolean shouldProceed() {
        return proceed;
    }
}