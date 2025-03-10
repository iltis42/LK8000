/*
 * LK8000 Tactical Flight Computer -  WWW.LK8000.IT
 * Released under GNU/GPL License v.2 or later
 * See CREDITS.TXT file for authors and copyrights
 *
 * File:   ComPort.h
 * Author: Bruno de Lacheisserie
 *
 * Created on 28 juillet 2013, 16:15
 */

#ifndef COMPORT_H
#define	COMPORT_H

#include "Sizes.h"
#include "Enums.h"
#include "Util/tstring.hpp"
#include "Poco/Event.h"
#include "Thread/Thread.hpp"

class ComPort : public Thread {
public:
    ComPort(int idx, const tstring& sName);

    ComPort() = delete;

    ComPort( const ComPort& ) = delete;
    ComPort& operator=( const ComPort& ) = delete;

    ComPort( ComPort&& ) = delete;
    ComPort& operator=( ComPort&& ) = delete;

    virtual bool StopRxThread();
    virtual bool StartRxThread();

    inline LPCTSTR GetPortName() const {
        return sPortName.c_str();
    }

    inline size_t GetPortIndex() const {
        return devIdx;
    }

    virtual bool Initialize() = 0;
    virtual bool Close();

    virtual void Flush() = 0;
    virtual void Purge() = 0;
    virtual void CancelWaitEvent() = 0;

    virtual int SetRxTimeout(int TimeOut) = 0;
    virtual unsigned long SetBaudrate(unsigned long) = 0;
    virtual unsigned long GetBaudrate() const = 0;

    virtual void UpdateStatus() = 0;
    virtual bool IsReady() = 0;

    bool Write(const void *data, size_t size);

    inline bool Write(uint8_t b) {
        return Write(&b, sizeof(b));
    }

#ifdef UNICODE
    bool WriteString(const wchar_t* Text) gcc_nonnull_all;
#endif
    bool WriteString(const char* Text) gcc_nonnull_all;

    virtual size_t Read(void* data, size_t size) = 0;

    template<typename T, size_t size>
    size_t Read(T (&data)[size]) {
        return Read(data, size * sizeof(T));
    }

    int GetChar();

protected:

    static
    void StatusMessage(MsgType_t type, const TCHAR *caption, const TCHAR *fmt, ...)
            gcc_printf(3,4) gcc_nonnull(3);

    void AddStatRx(unsigned dwBytes);
    void AddStatErrRx(unsigned dwBytes);
    void AddStatTx(unsigned dwBytes);
    void AddStatErrTx(unsigned dwBytes);

    void SetPortStatus(int Status);

    virtual unsigned RxThread() = 0;

    void ProcessChar(char c);

    auto GetProcessCharHandler() {
        return [&](char c) {
            ProcessChar(c);
        };
    }

    Poco::Event StopEvt;

private:

    using _NmeaString_t = TCHAR[MAX_NMEA_LEN];

    void Run() override;

    const size_t devIdx;
    const tstring sPortName;

    _NmeaString_t _NmeaString;
    TCHAR * pLastNmea;

    virtual bool Write_Impl(const void *data, size_t size) = 0;
};

#endif	/* COMPORT_H */
