/* Binder interface generated from IUserService.aidl with Android build-tools 36.0.0. */
package com.burgess.schedule.school.sync.golden;
public interface IUserService extends android.os.IInterface
{
  /** Default implementation for IUserService. */
  public static class Default implements com.burgess.schedule.school.sync.golden.IUserService
  {
    @Override public void exit() throws android.os.RemoteException
    {
    }
    @Override public int getUid() throws android.os.RemoteException
    {
      return 0;
    }
    @Override public java.lang.String ping() throws android.os.RemoteException
    {
      return null;
    }
    @Override public void destroy() throws android.os.RemoteException
    {
    }
    @Override
    public android.os.IBinder asBinder() {
      return null;
    }
  }
  /** Local-side IPC implementation stub class. */
  public static abstract class Stub extends android.os.Binder implements com.burgess.schedule.school.sync.golden.IUserService
  {
    /** Construct the stub and attach it to the interface. */
    @SuppressWarnings("this-escape")
    public Stub()
    {
      this.attachInterface(this, DESCRIPTOR);
    }
    /**
     * Cast an IBinder object into an com.burgess.schedule.school.sync.golden.IUserService interface,
     * generating a proxy if needed.
     */
    public static com.burgess.schedule.school.sync.golden.IUserService asInterface(android.os.IBinder obj)
    {
      if ((obj==null)) {
        return null;
      }
      android.os.IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
      if (((iin!=null)&&(iin instanceof com.burgess.schedule.school.sync.golden.IUserService))) {
        return ((com.burgess.schedule.school.sync.golden.IUserService)iin);
      }
      return new com.burgess.schedule.school.sync.golden.IUserService.Stub.Proxy(obj);
    }
    @Override public android.os.IBinder asBinder()
    {
      return this;
    }
    @Override public boolean onTransact(int code, android.os.Parcel data, android.os.Parcel reply, int flags) throws android.os.RemoteException
    {
      java.lang.String descriptor = DESCRIPTOR;
      if (code >= android.os.IBinder.FIRST_CALL_TRANSACTION && code <= android.os.IBinder.LAST_CALL_TRANSACTION) {
        data.enforceInterface(descriptor);
      }
      if (code == INTERFACE_TRANSACTION) {
        reply.writeString(descriptor);
        return true;
      }
      switch (code)
      {
        case TRANSACTION_exit:
        {
          this.exit();
          reply.writeNoException();
          break;
        }
        case TRANSACTION_getUid:
        {
          int _result = this.getUid();
          reply.writeNoException();
          reply.writeInt(_result);
          break;
        }
        case TRANSACTION_ping:
        {
          java.lang.String _result = this.ping();
          reply.writeNoException();
          reply.writeString(_result);
          break;
        }
        case TRANSACTION_destroy:
        {
          this.destroy();
          reply.writeNoException();
          break;
        }
        default:
        {
          return super.onTransact(code, data, reply, flags);
        }
      }
      return true;
    }
    private static class Proxy implements com.burgess.schedule.school.sync.golden.IUserService
    {
      private android.os.IBinder mRemote;
      Proxy(android.os.IBinder remote)
      {
        mRemote = remote;
      }
      @Override public android.os.IBinder asBinder()
      {
        return mRemote;
      }
      public java.lang.String getInterfaceDescriptor()
      {
        return DESCRIPTOR;
      }
      @Override public void exit() throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          boolean _status = mRemote.transact(Stub.TRANSACTION_exit, _data, _reply, 0);
          _reply.readException();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
      }
      @Override public int getUid() throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        int _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          boolean _status = mRemote.transact(Stub.TRANSACTION_getUid, _data, _reply, 0);
          _reply.readException();
          _result = _reply.readInt();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      @Override public java.lang.String ping() throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        java.lang.String _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          boolean _status = mRemote.transact(Stub.TRANSACTION_ping, _data, _reply, 0);
          _reply.readException();
          _result = _reply.readString();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      @Override public void destroy() throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          boolean _status = mRemote.transact(Stub.TRANSACTION_destroy, _data, _reply, 0);
          _reply.readException();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
      }
    }
    static final int TRANSACTION_exit = (android.os.IBinder.FIRST_CALL_TRANSACTION + 1);
    static final int TRANSACTION_getUid = (android.os.IBinder.FIRST_CALL_TRANSACTION + 2);
    static final int TRANSACTION_ping = (android.os.IBinder.FIRST_CALL_TRANSACTION + 4);
    static final int TRANSACTION_destroy = (android.os.IBinder.FIRST_CALL_TRANSACTION + 16777114);
  }
  /** @hide */
  public static final java.lang.String DESCRIPTOR = "com.burgess.schedule.school.sync.golden.IUserService";
  public void exit() throws android.os.RemoteException;
  public int getUid() throws android.os.RemoteException;
  public java.lang.String ping() throws android.os.RemoteException;
  public void destroy() throws android.os.RemoteException;
}
